/**
 * Dev pipeline: fast feedback — `./gradlew test` (no `-Pintegration`/Testcontainers), Docker build,
 * Trivy scan, deploy `circleguard-dev`. Optional SonarQube analysis (RUN_SONARQUBE).
 *
 * Docker and Kubernetes stages are mandatory. Use `docker/Dockerfile.jenkins` or mount the host
 * Docker socket (see `docker-compose.jenkins.yml`) so the agent has Docker CLI, kubectl, and a
 * reachable Kubernetes API.
 *
 * Images are tagged `dev-latest` (as in deploy/k8s/apps/dev/microservices.yaml). If a
 * `circleguard-k3s` container exists, images are imported to containerd via `ctr import`.
 */

// Optional notifications (Slack then email); never throws.
def notifyBuild(String status) {
    def color = (status == 'SUCCESS') ? 'good' : (status == 'UNSTABLE' ? 'warning' : 'danger')
    def line = "[CircleGuard][dev] ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER} — ${env.BUILD_URL}"
    try { slackSend(color: color, message: line) } catch (err) { echo "[notify] Slack skipped: ${err.message}" }
    try {
        if (env.NOTIFY_EMAIL?.trim()) {
            mail(to: env.NOTIFY_EMAIL, subject: "[CircleGuard][dev] ${status} #${env.BUILD_NUMBER}",
                 body: "Status: ${status}\nBuild: ${env.BUILD_URL}")
        }
    } catch (err) { echo "[notify] Email skipped: ${err.message}" }
}

pipeline {
    agent any
    options { timestamps() }
    parameters {
        booleanParam(name: 'RUN_SONARQUBE', defaultValue: false,
            description: 'Run SonarQube analysis (requires a SonarQube server configured in Jenkins).')
        string(name: 'SONARQUBE_SERVER', defaultValue: 'SonarQube',
            description: 'Name of the SonarQube server (Manage Jenkins → System).')
        booleanParam(name: 'TRIVY_FAIL_ON_FINDINGS', defaultValue: false,
            description: 'Fail the build on HIGH/CRITICAL image vulnerabilities (dev defaults to warn-only).')
        booleanParam(name: 'ENFORCE_COVERAGE', defaultValue: false,
            description: 'Fail the build if aggregated line coverage is below COVERAGE_MIN.')
        string(name: 'COVERAGE_MIN', defaultValue: '0.30',
            description: 'Minimum aggregated LINE coverage ratio (0.0–1.0) when ENFORCE_COVERAGE is on.')
        booleanParam(name: 'RUN_ZAP', defaultValue: false,
            description: 'Run an OWASP ZAP baseline (passive) DAST scan against the deployed gateway.')
        booleanParam(name: 'ZAP_FAIL_ON_FINDINGS', defaultValue: false,
            description: 'Fail the build on ZAP FAIL-tagged alerts (dev defaults to warn-only).')
        string(name: 'NOTIFY_EMAIL', defaultValue: '', description: 'Recipients for notifications (optional).')
    }
    environment {
        IMAGE_NAMESPACE = 'circleguard'
        TAG = "${env.GIT_COMMIT.take(7)}-${env.BUILD_NUMBER}"
        NOTIFY_EMAIL = "${params.NOTIFY_EMAIL}"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Gradle Unit Tests + Coverage') {
            steps {
                sh '''
                    set -eu
                    # Unit tests + aggregated JaCoCo coverage report (build/reports/jacoco/aggregate).
                    ./gradlew test jacocoAggregatedReport --parallel --build-cache
                '''
                script {
                    if (params.ENFORCE_COVERAGE) {
                        withEnv(["COVERAGE_MIN=${params.COVERAGE_MIN}"]) {
                            sh '''
                                set -eu
                                ./gradlew jacocoCoverageVerification -PcoverageMin="${COVERAGE_MIN}" --build-cache
                            '''
                        }
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml,**/build/test-results/integrationTest/*.xml'
                    archiveArtifacts artifacts: 'build/reports/jacoco/aggregate/**', allowEmptyArchive: true
                }
            }
        }
        stage('SonarQube Analysis') {
            when { expression { params.RUN_SONARQUBE } }
            steps {
                withSonarQubeEnv(params.SONARQUBE_SERVER) {
                    sh '''
                        set -eu
                        ./gradlew sonar --build-cache
                    '''
                }
            }
        }
        stage('Docker Build (six services)') {
            steps {
                script {
                    def svcs = [
                        'circleguard-auth-service',
                        'circleguard-identity-service',
                        'circleguard-form-service',
                        'circleguard-promotion-service',
                        'circleguard-notification-service',
                        'circleguard-gateway-service'
                    ]
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
                    '''
                    svcs.each { svcDir ->
                        def shortName = svcDir.replaceFirst(/^circleguard-/, '')
                        sh """
                            docker build -f docker/Dockerfile.service \\
                              --build-arg SERVICE_DIR=${svcDir} \\
                              -t ${IMAGE_NAMESPACE}/${shortName}:dev-${TAG} .
                        """
                    }
                    sh """
                        set -eu
                        for img in auth-service identity-service form-service promotion-service notification-service gateway-service; do
                            docker tag circleguard/\${img}:dev-${env.TAG} circleguard/\${img}:dev-latest
                        done
                    """
                    echo 'Tagged dev-* images as dev-latest for Kubernetes manifests.'
                }
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
                            IMGS="${IMGS} circleguard/${s}:dev-latest"
                        done
                        scripts/ci/trivy-scan.sh ${IMGS}
                    '''
                }
            }
        }
        stage('Deploy Kubernetes (dev)') {
            steps {
                sh '''
                    set -eu
                    K3S_CTR="${K3S_CONTAINER_NAME:-circleguard-k3s}"
                    IMGS="auth-service identity-service form-service promotion-service notification-service gateway-service"
                    if command -v docker >/dev/null 2>&1 && docker inspect "$K3S_CTR" >/dev/null 2>&1; then
                        echo "[INFO] Loading circleguard/*:dev-latest into k3s (${K3S_CTR}) via ctr import..."
                        for img in $IMGS; do
                            docker save "circleguard/${img}:dev-latest" | docker exec -i "$K3S_CTR" ctr -n k8s.io images import -
                        done
                    else
                        echo "[INFO] No local k3s container (${K3S_CTR}) or no Docker — cluster must pull dev-latest from a registry."
                    fi
                '''
                sh '''
                    set -eu
                    command -v kubectl >/dev/null 2>&1
                    kubectl get --raw=/version >/dev/null
                    kubectl apply -f deploy/k8s/namespaces.yaml
                    kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml
                    # Gateway needs Redis; promotion needs Postgres/Neo4j — wait infra before app pods start racing ImagePull + JVM.
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
                    kubectl apply -f deploy/k8s/apps/dev/microservices.yaml
                    TIMEOUT=600s
                    for d in circleguard-auth-service circleguard-identity-service circleguard-form-service circleguard-promotion-service circleguard-notification-service circleguard-gateway-service
                    do
                        echo "[INFO] kubectl rollout status deployment/${d} -n circleguard-dev --timeout=${TIMEOUT}"
                        kubectl rollout status "deployment/${d}" -n circleguard-dev --timeout="${TIMEOUT}"
                    done
                '''
            }
        }
        stage('OWASP ZAP DAST (dev)') {
            when { expression { params.RUN_ZAP } }
            steps {
                withEnv(["ZAP_FAIL_ON_FINDINGS=${params.ZAP_FAIL_ON_FINDINGS ? '1' : '0'}"]) {
                    sh '''
                        set -eu
                        chmod +x scripts/ci/zap-baseline.sh scripts/ci/run-zap-with-kube-port-forward.sh
                        # Port-forwards the deployed gateway to 127.0.0.1 and runs a passive baseline scan.
                        scripts/ci/run-zap-with-kube-port-forward.sh circleguard-dev
                    '''
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/reports/zap/**', allowEmptyArchive: true
                }
            }
        }
    }
    post {
        success { script { notifyBuild('SUCCESS') } }
        unstable { script { notifyBuild('UNSTABLE') } }
        failure { script { notifyBuild('FAILURE') } }
    }
}
