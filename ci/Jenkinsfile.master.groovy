/**
 * Master/prod pipeline: gates on tests, deploy prod namespace, smoke E2E, Release Notes artifact.
 *
 * Integration, Docker build/push, Kubernetes deploy, and smoke E2E stages are mandatory. The Jenkins
 * agent must provide Docker, kubectl, a reachable Kubernetes API, and the E2E_* URLs required by tests.
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
                                command -v docker >/dev/null 2>&1
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
            steps {
                echo "[INFO] Manifests use ${params.IMAGE_NAMESPACE ?: 'demitard'}/*:prod-latest; build/push stage must publish those tags first."
                sh '''
                    set -eu
                    command -v kubectl >/dev/null 2>&1
                    kubectl get --raw=/version >/dev/null
                    # Cluster preflight: when k3s runs in Docker (docker-compose.jenkins.yml) and the container is recreated,
                    # the previous Kubernetes node stays in etcd as NotReady ("ghost"), and any pod bound to it remains there
                    # because the unreachable taint is NoSchedule (not NoExecute). The Deployment then reports
                    # `0/1 available` forever and `kubectl rollout status` times out. Below we delete every NotReady node and
                    # force-evict its orphan pods so the schedulers re-place them on the live node before waiting for rollouts.
                    echo "[INFO] Pruning ghost (NotReady) Kubernetes nodes (if any)..."
                    GHOSTS=$(kubectl get nodes --no-headers \
                        | awk '$2 != "Ready" {print $1}' || true)
                    if [ -n "${GHOSTS}" ]; then
                        for n in ${GHOSTS}; do
                            echo "[INFO] Force-deleting orphan pods on ghost node ${n}"
                            kubectl get pods --all-namespaces \
                                --field-selector="spec.nodeName=${n}" \
                                -o json \
                                | kubectl delete -f - --ignore-not-found --force --grace-period=0 || true
                            echo "[INFO] Deleting ghost node ${n}"
                            kubectl delete node "${n}" --ignore-not-found || true
                        done
                    else
                        echo "[INFO] No ghost nodes detected."
                    fi

                    # Same prerequisite chain as dev: namespaces + infra must exist and be Ready before auth/postgres consumers start.
                    kubectl apply -f deploy/k8s/namespaces.yaml
                    kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml

                    # If existing infra Deployments still have pods on a (just-deleted) ghost node, the Deployment is "unchanged"
                    # and rollout status would report 0 available. Force a fresh rollout to reschedule onto the live node.
                    for d in postgres redis neo4j; do
                        STUCK=$(kubectl get pods -n circleguard-infra -l "app=${d}" \
                            -o jsonpath='{range .items[?(@.status.conditions[?(@.type=="Ready")].status=="False")]}{.metadata.name}{"\\n"}{end}' \
                            | tr -d '[:space:]') || STUCK=""
                        if [ -n "${STUCK}" ]; then
                            echo "[INFO] ${d} has not-Ready pod(s); restarting deployment to reschedule."
                            kubectl rollout restart "deployment/${d}" -n circleguard-infra
                        fi
                    done

                    kubectl rollout status deployment/postgres -n circleguard-infra --timeout=300s
                    kubectl delete job postgres-ensure-databases -n circleguard-infra --ignore-not-found
                    kubectl apply -f deploy/k8s/infra/postgres-ensure-databases.yaml
                    kubectl wait --for=condition=complete job/postgres-ensure-databases -n circleguard-infra --timeout=180s
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
            steps {
                withEnv(['E2E_RUN=true']) {
                    sh './gradlew :e2e-tests:test --parallel --build-cache'
                }
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
                            printf -- '- Integration and E2E tests run as mandatory pipeline stages.\\n'
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
