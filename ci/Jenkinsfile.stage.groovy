/**
 * Stage pipeline: Gradle tests (+integration), optional SonarQube + Quality Gate, Docker build,
 * Trivy scan, deploy stage namespace, Locust performance report, and E2E smoke tests.
 *
 * Integration, deploy, Locust, and E2E stages are mandatory. SonarQube runs only when
 * RUN_SONARQUBE=true (needs a configured server). E2E uses kubectl port-forward (see
 * scripts/ci/run-e2e-with-kube-port-forward.sh) — no E2E_* job parameters.
 */

// Optional notifications (Slack then email); never throws.
def notifyBuild(String status) {
    def color = (status == 'SUCCESS') ? 'good' : (status == 'UNSTABLE' ? 'warning' : 'danger')
    def line = "[CircleGuard][stage] ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER} — ${env.BUILD_URL}"
    try { slackSend(color: color, message: line) } catch (err) { echo "[notify] Slack skipped: ${err.message}" }
    try {
        if (env.NOTIFY_EMAIL?.trim()) {
            mail(to: env.NOTIFY_EMAIL, subject: "[CircleGuard][stage] ${status} #${env.BUILD_NUMBER}",
                 body: "Status: ${status}\nBuild: ${env.BUILD_URL}")
        }
    } catch (err) { echo "[notify] Email skipped: ${err.message}" }
}

pipeline {
    agent any
    options { timestamps() }
    parameters {
        booleanParam(name: 'RUN_SONARQUBE', defaultValue: false,
            description: 'Run SonarQube analysis + Quality Gate (requires a SonarQube server configured in Jenkins).')
        string(name: 'SONARQUBE_SERVER', defaultValue: 'SonarQube',
            description: 'Name of the SonarQube server (Manage Jenkins → System).')
        booleanParam(name: 'TRIVY_FAIL_ON_FINDINGS', defaultValue: false,
            description: 'Fail the build on HIGH/CRITICAL image vulnerabilities (stage defaults to warn-only).')
        string(name: 'NOTIFY_EMAIL', defaultValue: '', description: 'Recipients for notifications (optional).')
    }
    environment {
        IMAGE_NAMESPACE = 'circleguard'
        LOCUST_USERS = '20'
        LOCUST_SPAWN_RATE = '2'
        LOCUST_RUN_TIME = '2m'
        NOTIFY_EMAIL = "${params.NOTIFY_EMAIL}"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Gradle Tests (with integration)') {
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
                        if [ -S /var/run/docker.sock ]; then export DOCKER_HOST=unix:///var/run/docker.sock; fi
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
        stage('Docker Build (stage-latest)') {
            steps {
                sh '''
                    set -eu
                    command -v docker >/dev/null 2>&1
                    ./gradlew \\
                      :services:circleguard-auth-service:bootJar \\
                      :services:circleguard-identity-service:bootJar \\
                      :services:circleguard-form-service:bootJar \\
                      :services:circleguard-promotion-service:bootJar \\
                      :services:circleguard-notification-service:bootJar \\
                      :services:circleguard-gateway-service:bootJar \\
                      -x test --parallel --build-cache
                    for svcDir in circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service
                    do
                        SHORT="${svcDir#circleguard-}"
                        docker build -f docker/Dockerfile.service --build-arg "SERVICE_DIR=${svcDir}" -t "${IMAGE_NAMESPACE}/${SHORT}:stage-latest" .
                    done
                '''
            }
        }
        stage('Trivy Image Scan') {
            steps {
                withEnv(["TRIVY_EXIT_CODE=${params.TRIVY_FAIL_ON_FINDINGS ? '1' : '0'}"]) {
                    sh '''
                        set -eu
                        chmod +x scripts/ci/trivy-scan.sh
                        IMGS=""
                        for s in auth-service identity-service form-service promotion-service notification-service gateway-service; do
                            IMGS="${IMGS} circleguard/${s}:stage-latest"
                        done
                        scripts/ci/trivy-scan.sh ${IMGS}
                    '''
                }
            }
        }
        stage('Deploy Kubernetes (stage)') {
            steps {
                sh '''
                    set -eu
                    K3S_CTR="${K3S_CONTAINER_NAME:-circleguard-k3s}"
                    IMGS="auth-service identity-service form-service promotion-service notification-service gateway-service"
                    if docker inspect "$K3S_CTR" >/dev/null 2>&1; then
                        echo "[INFO] Loading circleguard/*:stage-latest into k3s (${K3S_CTR}) via ctr import..."
                        for img in $IMGS; do
                            docker save "circleguard/${img}:stage-latest" | docker exec -i "$K3S_CTR" ctr -n k8s.io images import -
                        done
                    else
                        echo "[INFO] No local k3s container (${K3S_CTR}); cluster must pull stage-latest from a registry."
                    fi
                '''
                sh '''
                    set -eu
                    command -v kubectl >/dev/null 2>&1
                    kubectl get --raw=/version >/dev/null
                    kubectl apply -f deploy/k8s/apps/stage/microservices.yaml
                '''
            }
        }
        stage('Locust (stage ingress)') {
            steps {
                sh '''
                    set -eu
                    mkdir -p build
                    if ! command -v python3 >/dev/null 2>&1; then
                        echo "[ERROR] python3 not found. Rebuild Jenkins image (includes python3-pip): docker compose -f docker-compose.jenkins.yml build --no-cache" >&2
                        exit 127
                    fi
                    VENV="${WORKSPACE}/.jenkins-locust-venv"
                    export PIP_CACHE_DIR="${WORKSPACE}/.pip-cache"
                    mkdir -p build "${PIP_CACHE_DIR}"
                    python3 -m venv "${VENV}"
                    "${VENV}/bin/pip" install -r tests/performance/requirements-locust.txt
                    chmod +x scripts/ci/run-locust-with-kube-port-forward.sh
                    bash scripts/ci/run-locust-with-kube-port-forward.sh circleguard-stage \
                      "${VENV}/bin/locust" -f tests/performance/locustfile.py \
                      --headless -u ${LOCUST_USERS} -r ${LOCUST_SPAWN_RATE} --run-time ${LOCUST_RUN_TIME} \
                      --html build/locust-report-stage.html --csv build/locust-stage
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/locust-report-stage.html,build/locust-stage*.csv', allowEmptyArchive: true
                }
            }
        }
        stage('E2E Smoke') {
            steps {
                sh '''
                    set -eu
                    chmod +x scripts/ci/run-e2e-with-kube-port-forward.sh
                    bash scripts/ci/run-e2e-with-kube-port-forward.sh circleguard-stage
                '''
            }
        }
    }
    post {
        success { script { notifyBuild('SUCCESS') } }
        unstable { script { notifyBuild('UNSTABLE') } }
        failure { script { notifyBuild('FAILURE') } }
    }
}
