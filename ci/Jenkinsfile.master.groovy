/**
 * Master/prod pipeline: gates on tests, deploy prod namespace, smoke E2E, Release Notes artifact.
 *
 * Integration tests (Testcontainers) run only when RUN_INTEGRATION_TESTS is enabled (job parameter or
 * env) and the Jenkins agent can use the host Docker socket — otherwise they fail inside Jenkins.
 *
 * The Kubernetes deploy stage runs only when `kubectl` is available and `kubectl get --raw=/version`
 * succeeds (real API server). Wrong kubeconfig (e.g. HTML login instead of OpenAPI) skips the stage.
 * To force-skip, set SKIP_K8S_DEPLOY=true as a job env var.
 *
 * Docker Hub: master expects images `${IMAGE_NAMESPACE}/*:prod-latest` (default namespace `demitard`).
 * Jenkins credential `dockerhub-credentials` (Kind: Username with password): usuario Docker Hub + contraseña o Access Token.
 * Override ID with parameter DOCKERHUB_CREDENTIALS_ID if needed.
 */
pipeline {
    agent any
    parameters {
        string(name: 'RELEASE_VERSION', defaultValue: '1.0.0', description: 'Semantic version for release notes')
        string(
            name: 'IMAGE_NAMESPACE',
            defaultValue: 'demitard',
            description: 'Docker Hub usuario/organización (ej. demitard); debe coincidir con deploy/k8s/apps/master/microservices.yaml',
        )
        string(
            name: 'DOCKERHUB_CREDENTIALS_ID',
            defaultValue: 'dockerhub-credentials',
            description: 'ID de credencial Jenkins (Username with password) para docker login + push',
        )
        booleanParam(
            name: 'RUN_INTEGRATION_TESTS',
            defaultValue: false,
            description: 'Run Gradle -Pintegration (Testcontainers; requires Docker usable from the agent)',
        )
        booleanParam(
            name: 'SKIP_DOCKER_BUILD',
            defaultValue: false,
            description: 'Saltar build/push de imágenes prod-latest (solo si ya están en el registry)',
        )
        booleanParam(
            name: 'SKIP_K8S_DEPLOY',
            defaultValue: false,
            description: 'Saltar despliegue Kubernetes (útil si el agente no tiene kubectl o cluster)',
        )
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Gradle Unit Tests') {
            steps {
                sh '''
                    set -eu
                    ./gradlew test --parallel --build-cache
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml,**/build/test-results/integrationTest/*.xml'
                }
            }
        }
        stage('Integration Tests') {
            when {
                expression {
                    if (env.SKIP_INTEGRATION_TESTS == 'true') {
                        return false
                    }
                    return env.RUN_INTEGRATION_TESTS == 'true' || params.RUN_INTEGRATION_TESTS == true
                }
            }
            environment {
                DOCKER_HOST = 'unix:///var/run/docker.sock'
            }
            steps {
                sh '''
                    set -eu
                    if [ -S /var/run/docker.sock ]; then export DOCKER_HOST=unix:///var/run/docker.sock; fi
                    ./gradlew test --parallel --build-cache -Pintegration
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml,**/build/test-results/integrationTest/*.xml'
                }
            }
        }
        stage('Docker Build & Push (prod-latest)') {
            when {
                expression {
                    if (params.SKIP_DOCKER_BUILD == true || env.SKIP_DOCKER_BUILD == 'true') {
                        echo 'Stage saltado por SKIP_DOCKER_BUILD=true.'
                        return false
                    }
                    def hasDocker = sh(script: 'command -v docker >/dev/null 2>&1', returnStatus: true) == 0
                    if (!hasDocker) {
                        echo '[WARN] `docker` CLI no disponible. Stage omitido. Usa Dockerfile.jenkins o socket Docker.'
                    }
                    return hasDocker
                }
            }
            steps {
                script {
                    def ns = params.IMAGE_NAMESPACE?.trim() ?: 'demitard'
                    def credId = params.DOCKERHUB_CREDENTIALS_ID?.trim() ?: 'dockerhub-credentials'
                    withCredentials([
                        usernamePassword(
                            credentialsId: credId,
                            usernameVariable: 'DOCKERHUB_USERNAME',
                            passwordVariable: 'DOCKERHUB_PASSWORD',
                        ),
                    ]) {
                        withEnv(["DOCKER_NS=${ns}"]) {
                            sh '''
                                set -eu
                                NS="${DOCKER_NS}"
                                # Single Gradle run packages all boot JARs on the agent (reuses deps from test stage); Docker only layers JRE + JAR (no Gradle inside each image).
                                ./gradlew \\
                                  :services:circleguard-auth-service:bootJar \\
                                  :services:circleguard-identity-service:bootJar \\
                                  :services:circleguard-form-service:bootJar \\
                                  :services:circleguard-promotion-service:bootJar \\
                                  :services:circleguard-notification-service:bootJar \\
                                  :services:circleguard-gateway-service:bootJar \\
                                  -x test --parallel --build-cache
                                echo "${DOCKERHUB_PASSWORD}" | docker login -u "${DOCKERHUB_USERNAME}" --password-stdin
                                for svcDir in circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service
                                do
                                    SHORT="${svcDir#circleguard-}"
                                    echo "[INFO] docker build + push ${NS}/${SHORT}:prod-latest"
                                    docker build -f docker/Dockerfile.service --build-arg "SERVICE_DIR=${svcDir}" -t "${NS}/${SHORT}:prod-latest" .
                                    docker push "${NS}/${SHORT}:prod-latest"
                                done
                                echo "[INFO] Si Docker Desktop muestra contenedores detenidos con nombre aleatorio tras el build, suelen ser restos de BuildKit; puedes borrarlos con: docker container prune -f"
                            '''
                        }
                    }
                }
                echo "[INFO] Imágenes publicadas como ${params.IMAGE_NAMESPACE ?: 'demitard'}/*:prod-latest en Docker Hub."
            }
        }
        stage('Deploy Kubernetes (master)') {
            when {
                expression {
                    if (params.SKIP_K8S_DEPLOY == true || env.SKIP_K8S_DEPLOY == 'true') {
                        echo 'Stage saltado por SKIP_K8S_DEPLOY=true.'
                        return false
                    }
                    def hasKubectl = sh(script: 'command -v kubectl >/dev/null 2>&1', returnStatus: true) == 0
                    if (!hasKubectl) {
                        echo '[WARN] `kubectl` no disponible en el agente Jenkins. Stage omitido. ' +
                            'Instala kubectl o usa la imagen `docker/Dockerfile.jenkins`. ' +
                            'Para silenciar este aviso, marca SKIP_K8S_DEPLOY=true.'
                        return false
                    }
                    def clusterOk = sh(script: 'kubectl get --raw=/version >/dev/null 2>&1', returnStatus: true) == 0
                    if (!clusterOk) {
                        echo '[WARN] kubectl está instalado pero la API del cluster no responde (kubeconfig incorrecto, ' +
                            'proxy o página de login en lugar del servidor Kubernetes). Stage omitido. ' +
                            'Corrige KUBECONFIG o marca SKIP_K8S_DEPLOY=true.'
                    }
                    return clusterOk
                }
            }
            steps {
                echo "[INFO] Manifests use ${params.IMAGE_NAMESPACE ?: 'demitard'}/*:prod-latest — build/push stage debe haber publicado esos tags (o SKIP_DOCKER_BUILD si ya existen)."
                sh '''
                    set -eu
                    # Same prerequisite chain as dev: namespaces + infra must exist and be Ready before auth/postgres consumers start.
                    kubectl apply -f deploy/k8s/namespaces.yaml
                    kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml
                    kubectl rollout status deployment/postgres -n circleguard-infra --timeout=300s
                    kubectl rollout status deployment/redis -n circleguard-infra --timeout=300s
                    kubectl rollout status deployment/neo4j -n circleguard-infra --timeout=300s
                    kubectl apply -f deploy/k8s/infra/kafka-zookeeper.yaml
                    kubectl rollout status deployment/zookeeper -n circleguard-infra --timeout=300s
                    kubectl rollout status deployment/kafka -n circleguard-infra --timeout=300s
                    kubectl apply -f deploy/k8s/infra/openldap.yaml
                    kubectl rollout status deployment/openldap -n circleguard-infra --timeout=300s
                    kubectl apply -f deploy/k8s/apps/master/microservices.yaml
                    NS=circleguard-master
                    TIMEOUT=600s
                    for d in circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service
                    do
                        echo "[INFO] kubectl rollout status deployment/${d} -n ${NS} --timeout=${TIMEOUT}"
                        kubectl rollout status "deployment/${d}" -n "${NS}" --timeout="${TIMEOUT}" || {
                            echo "[ERROR] Rollout failed for ${d} — describe / pods / logs:"
                            kubectl describe "deployment/${d}" -n "${NS}" || true
                            kubectl get pods -n "${NS}" -l "app=${d}" -o wide || true
                            echo "[INFO] Logs per pod (evita pod viejo en Terminating sin logs):"
                            kubectl get pods -n "${NS}" -l "app=${d}" -o name | while read -r podpath
                            do
                              pname="${podpath#pod/}"
                              echo "---- ${pname} ----"
                              kubectl logs -n "${NS}" "${pname}" --tail=120 --all-containers=true 2>&1 || true
                            done
                            exit 1
                        }
                    done
                '''
            }
        }
        stage('Smoke E2E') {
            when { environment name: 'E2E_RUN', value: 'true' }
            steps {
                sh './gradlew :e2e-tests:test --parallel --build-cache'
            }
        }
        stage('Release Notes') {
            steps {
                withEnv(["RELEASE_VERSION=${params.RELEASE_VERSION}"]) {
                    sh '''
                        set -eu
                        mkdir -p build
                        DATE=$(date -u +%Y-%m-%d)
                        LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
                        if [ -n "$LAST_TAG" ]; then
                            RANGE="$LAST_TAG..HEAD"
                            SINCE="$LAST_TAG"
                        else
                            RANGE="HEAD"
                            SINCE="repository start"
                        fi
                        {
                            printf '# Release %s — %s\\n\\n' "$RELEASE_VERSION" "$DATE"
                            printf '## Summary\\n'
                            printf 'Automated release notes for CircleGuard microservices (Change Management).\\n\\n'
                            printf '## Changes since %s\\n' "$SINCE"
                            git log "$RANGE" --pretty=format:"- %s (%h)" || printf -- '- No new commits since last tag.'
                            printf '\\n\\n## Deployment notes\\n'
                            printf -- '- Verify Kubernetes namespaces and image tags before rollout.\\n'
                            printf -- '- Run integration tests with `-Pintegration` and E2E with `E2E_RUN=true`.\\n'
                            printf -- '- Rollback: `kubectl rollout undo deployment/<name> -n circleguard-master`\\n\\n'
                            printf '## Risk & testing\\n'
                            printf -- '- Performance: review Locust HTML report (p95 latency, RPS, failure rate).\\n'
                        } > build/RELEASE_NOTES.md
                        echo "Wrote build/RELEASE_NOTES.md"
                    '''
                }
                archiveArtifacts artifacts: 'build/RELEASE_NOTES.md', fingerprint: true
            }
        }
    }
    post {
        success {
            echo 'Tag repository and publish GitHub Release using generated RELEASE_NOTES.md when ready.'
        }
    }
}
