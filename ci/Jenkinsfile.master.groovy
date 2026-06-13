/**
 * Master/prod pipeline (advanced CI/CD).
 *
 * Flow: checkout -> semantic version -> unit+integration tests -> SonarQube + Quality Gate ->
 *       build images -> Trivy scan -> push (Docker Hub OR ECR) -> manual approval ->
 *       deploy circleguard-master -> smoke E2E -> release notes -> tag. Notifications on finish.
 *
 * Every advanced stage degrades gracefully: SonarQube runs only when RUN_SONARQUBE=true (needs a
 * configured server), Trivy skips on agents without trivy/docker, ECR is opt-in via REGISTRY_TYPE,
 * and notifications no-op when Slack/SMTP aren't configured.
 *
 * Agent prerequisites: JDK 21, ./gradlew, Docker, kubectl + reachable cluster
 * (docker-compose.jenkins.yml). For REGISTRY_TYPE=ecr the agent also needs the AWS CLI.
 */

// Tries Slack then email; both optional. Never throws (notifications must not fail a build).
def notifyBuild(String status) {
    def color = (status == 'SUCCESS') ? 'good' : (status == 'UNSTABLE' ? 'warning' : 'danger')
    def subject = "[CircleGuard][master] ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER} (v${env.RELEASE_VERSION ?: '?'})"
    def line = "${subject} — ${env.BUILD_URL}"
    try {
        slackSend(color: color, message: line)
    } catch (err) {
        echo "[notify] Slack skipped (${err.getClass().getSimpleName()}): ${err.message}"
    }
    try {
        if (env.NOTIFY_EMAIL?.trim()) {
            mail(to: env.NOTIFY_EMAIL, subject: subject,
                 body: "Status: ${status}\nVersion: ${env.RELEASE_VERSION}\nBuild: ${env.BUILD_URL}")
        }
    } catch (err) {
        echo "[notify] Email skipped (${err.getClass().getSimpleName()}): ${err.message}"
    }
}

pipeline {
    agent any
    options {
        timestamps()
        disableConcurrentBuilds()
    }
    parameters {
        booleanParam(name: 'AUTO_VERSION', defaultValue: true,
            description: 'Compute the release version from Conventional Commits (scripts/ci/semantic-version.sh). If false, use RELEASE_VERSION below.')
        string(name: 'RELEASE_VERSION', defaultValue: '1.0.0',
            description: 'Manual semantic version, used only when AUTO_VERSION=false.')
        choice(name: 'REGISTRY_TYPE', choices: ['dockerhub', 'ecr'],
            description: 'Target container registry for the prod images.')
        string(name: 'IMAGE_NAMESPACE', defaultValue: 'demitard',
            description: 'Docker Hub user/org (must match deploy/k8s/apps/master/microservices.yaml). Used when REGISTRY_TYPE=dockerhub.')
        string(name: 'DOCKERHUB_CREDENTIALS_ID', defaultValue: 'dockerhub-credentials',
            description: 'Jenkins credential (Username+password) for Docker Hub.')
        string(name: 'ECR_REGISTRY', defaultValue: '',
            description: 'ECR registry host <account>.dkr.ecr.<region>.amazonaws.com (from terraform/shared output ecr_repository_urls). Used when REGISTRY_TYPE=ecr.')
        string(name: 'AWS_REGION', defaultValue: 'us-east-1', description: 'AWS region for ECR.')
        string(name: 'AWS_CREDENTIALS_ID', defaultValue: 'aws-credentials',
            description: 'Jenkins credential (Username+password = AWS access key id + secret) for ECR login.')
        booleanParam(name: 'RUN_SONARQUBE', defaultValue: false,
            description: 'Run SonarQube analysis + Quality Gate (requires a SonarQube server configured in Jenkins).')
        string(name: 'SONARQUBE_SERVER', defaultValue: 'SonarQube',
            description: 'Name of the SonarQube server (Manage Jenkins → System → SonarQube servers).')
        booleanParam(name: 'TRIVY_FAIL_ON_FINDINGS', defaultValue: true,
            description: 'Fail the build on HIGH/CRITICAL image vulnerabilities (ignores unfixed CVEs).')
        booleanParam(name: 'REQUIRE_APPROVAL', defaultValue: true,
            description: 'Require manual approval before deploying to prod.')
        booleanParam(name: 'PUSH_GIT_TAG', defaultValue: false,
            description: 'On success, create and push the git tag v<version> (needs push credentials on the agent).')
        string(name: 'NOTIFY_EMAIL', defaultValue: '',
            description: 'Comma-separated recipients for build notifications (optional).')
    }
    environment {
        NOTIFY_EMAIL = "${params.NOTIFY_EMAIL}"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Semantic Version') {
            steps {
                script {
                    if (params.AUTO_VERSION) {
                        env.RELEASE_VERSION = sh(
                            script: 'bash scripts/ci/semantic-version.sh',
                            returnStdout: true).trim()
                    } else {
                        env.RELEASE_VERSION = params.RELEASE_VERSION
                    }
                    echo "Release version: ${env.RELEASE_VERSION} (auto=${params.AUTO_VERSION})"
                    currentBuild.displayName = "#${env.BUILD_NUMBER} v${env.RELEASE_VERSION}"
                }
            }
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
        stage('SonarQube Analysis') {
            when { expression { params.RUN_SONARQUBE } }
            steps {
                withSonarQubeEnv(params.SONARQUBE_SERVER) {
                    sh '''
                        set -eu
                        ./gradlew sonar --build-cache -Pintegration
                    '''
                }
            }
        }
        stage('Quality Gate') {
            when { expression { params.RUN_SONARQUBE } }
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }
        stage('Build Images') {
            steps {
                withEnv(["VERSION=${env.RELEASE_VERSION}"]) {
                    sh '''
                        set -eu
                        command -v docker >/dev/null 2>&1
                        # One Gradle run packages all boot JARs; Docker only layers JRE + JAR.
                        ./gradlew \\
                          :services:circleguard-auth-service:bootJar \\
                          :services:circleguard-identity-service:bootJar \\
                          :services:circleguard-form-service:bootJar \\
                          :services:circleguard-promotion-service:bootJar \\
                          :services:circleguard-notification-service:bootJar \\
                          :services:circleguard-gateway-service:bootJar \\
                          -x test --parallel --build-cache
                        # Build registry-neutral local tags; the Push stage re-tags to the chosen registry.
                        for svcDir in circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service
                        do
                            SHORT="${svcDir#circleguard-}"
                            docker build -f docker/Dockerfile.service --build-arg "SERVICE_DIR=${svcDir}" \\
                              -t "circleguard/${SHORT}:prod-${VERSION}" \\
                              -t "circleguard/${SHORT}:prod-latest" .
                        done
                    '''
                }
            }
        }
        stage('Trivy Image Scan') {
            steps {
                withEnv([
                    "VERSION=${env.RELEASE_VERSION}",
                    "TRIVY_EXIT_CODE=${params.TRIVY_FAIL_ON_FINDINGS ? '1' : '0'}",
                ]) {
                    sh '''
                        set -eu
                        chmod +x scripts/ci/trivy-scan.sh
                        IMGS=""
                        for s in auth-service identity-service form-service promotion-service notification-service gateway-service; do
                            IMGS="${IMGS} circleguard/${s}:prod-${VERSION}"
                        done
                        scripts/ci/trivy-scan.sh ${IMGS}
                    '''
                }
            }
        }
        stage('Push Images') {
            steps {
                script {
                    def svcs = 'circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service'
                    if (params.REGISTRY_TYPE == 'ecr') {
                        withCredentials([usernamePassword(
                            credentialsId: params.AWS_CREDENTIALS_ID,
                            usernameVariable: 'AWS_ACCESS_KEY_ID',
                            passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            withEnv([
                                "AWS_REGION=${params.AWS_REGION}",
                                "ECR_REGISTRY=${params.ECR_REGISTRY}",
                                "VERSION=${env.RELEASE_VERSION}",
                                "SVCS=${svcs}",
                            ]) {
                                sh '''
                                    set -eu
                                    : "${ECR_REGISTRY:?Set ECR_REGISTRY (e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com)}"
                                    command -v aws >/dev/null 2>&1 || { echo "[ERROR] aws CLI required for REGISTRY_TYPE=ecr"; exit 1; }
                                    aws ecr get-login-password --region "$AWS_REGION" \\
                                      | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                                    for svcDir in $SVCS; do
                                        SHORT="${svcDir#circleguard-}"
                                        REPO="${ECR_REGISTRY}/circleguard/${SHORT}"
                                        docker tag "circleguard/${SHORT}:prod-${VERSION}" "${REPO}:prod-${VERSION}"
                                        docker tag "circleguard/${SHORT}:prod-latest"     "${REPO}:prod-latest"
                                        docker push "${REPO}:prod-${VERSION}"
                                        docker push "${REPO}:prod-latest"
                                    done
                                    echo "[INFO] Pushed to ECR ${ECR_REGISTRY}/circleguard/*. NOTE: the master manifests reference Docker Hub; point images at ECR (kustomize override / kubectl set image) for an ECR-backed deploy."
                                '''
                            }
                        }
                    } else {
                        withCredentials([usernamePassword(
                            credentialsId: params.DOCKERHUB_CREDENTIALS_ID,
                            usernameVariable: 'DOCKERHUB_USERNAME',
                            passwordVariable: 'DOCKERHUB_PASSWORD')]) {
                            withEnv([
                                "NS=${params.IMAGE_NAMESPACE}",
                                "VERSION=${env.RELEASE_VERSION}",
                                "SVCS=${svcs}",
                            ]) {
                                sh '''
                                    set -eu
                                    echo "${DOCKERHUB_PASSWORD}" | docker login -u "${DOCKERHUB_USERNAME}" --password-stdin
                                    for svcDir in $SVCS; do
                                        SHORT="${svcDir#circleguard-}"
                                        docker tag "circleguard/${SHORT}:prod-${VERSION}" "${NS}/${SHORT}:prod-${VERSION}"
                                        docker tag "circleguard/${SHORT}:prod-latest"     "${NS}/${SHORT}:prod-latest"
                                        docker push "${NS}/${SHORT}:prod-${VERSION}"
                                        docker push "${NS}/${SHORT}:prod-latest"
                                    done
                                '''
                            }
                        }
                        echo "[INFO] Pushed ${params.IMAGE_NAMESPACE}/*:prod-${env.RELEASE_VERSION} and :prod-latest to Docker Hub."
                    }
                }
            }
        }
        stage('Approval (prod)') {
            when { expression { params.REQUIRE_APPROVAL } }
            steps {
                timeout(time: 60, unit: 'MINUTES') {
                    script {
                        def approver = input(
                            message: "Deploy v${env.RELEASE_VERSION} to PROD (circleguard-master)?",
                            ok: 'Deploy',
                            submitterParameter: 'APPROVER')
                        echo "Prod deploy approved by: ${approver}"
                    }
                }
            }
        }
        stage('Deploy Kubernetes (master)') {
            steps {
                echo "[INFO] Manifests use ${params.IMAGE_NAMESPACE}/*:prod-latest; build/push stage must publish those tags first."
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
                        STUCK=""
                        for pod in $(kubectl get pods -n circleguard-infra -l "app=${d}" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null); do
                            r=$(kubectl get pod -n circleguard-infra "${pod}" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
                            if [ "${r}" != "True" ]; then
                                STUCK="${pod}"
                                break
                            fi
                        done
                        if [ -n "${STUCK}" ]; then
                            echo "[INFO] ${d} has not-Ready pod (${STUCK}); restarting deployment to reschedule."
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
                sh '''
                    set -eu
                    chmod +x scripts/ci/run-e2e-with-kube-port-forward.sh
                    bash scripts/ci/run-e2e-with-kube-port-forward.sh circleguard-master
                '''
            }
        }
        stage('Release Notes') {
            steps {
                withEnv(["RELEASE_VERSION=${env.RELEASE_VERSION}"]) {
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
        stage('Tag Release') {
            when { expression { params.PUSH_GIT_TAG } }
            steps {
                withEnv(["VERSION=${env.RELEASE_VERSION}"]) {
                    sh '''
                        set -eu
                        if git rev-parse "v${VERSION}" >/dev/null 2>&1; then
                            echo "[INFO] Tag v${VERSION} already exists; skipping."
                        else
                            git tag -a "v${VERSION}" -m "Release v${VERSION}"
                            git push origin "v${VERSION}"
                            echo "[INFO] Pushed tag v${VERSION}."
                        fi
                    '''
                }
            }
        }
    }
    post {
        success {
            script { notifyBuild('SUCCESS') }
            echo "Release v${env.RELEASE_VERSION} deployed. Publish a GitHub Release using build/RELEASE_NOTES.md when ready."
        }
        unstable { script { notifyBuild('UNSTABLE') } }
        failure { script { notifyBuild('FAILURE') } }
    }
}
