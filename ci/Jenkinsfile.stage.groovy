/**
 * Stage pipeline: full tests including integration (-Pintegration), deploy stage namespace,
 * optional Locust performance report archived as artifact.
 */
pipeline {
    agent any
    environment {
        LOCUST_USERS = '20'
        LOCUST_SPAWN_RATE = '2'
        LOCUST_RUN_TIME = '2m'
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Gradle Tests (with integration)') {
            steps {
                sh './gradlew test --no-daemon -Pintegration'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
                }
            }
        }
        stage('Docker Build & Push (stage tag)') {
            steps {
                echo 'Build/push stage images (reuse docker/Dockerfile.service with stage tags).'
            }
        }
        stage('Deploy Kubernetes (stage)') {
            steps {
                sh 'kubectl apply -f deploy/k8s/apps/stage/microservices.yaml'
            }
        }
        stage('Locust (stage ingress)') {
            steps {
                sh '''
                    pip install -r tests/performance/requirements-locust.txt
                    locust -f tests/performance/locustfile.py \
                      --headless -u ${LOCUST_USERS} -r ${LOCUST_SPAWN_RATE} --run-time ${LOCUST_RUN_TIME} \
                      --html build/locust-report-stage.html --csv build/locust-stage || true
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/locust-report-stage.html,build/locust-stage*.csv', allowEmptyArchive: true
                }
            }
        }
        stage('E2E (optional)') {
            steps {
                echo 'Set E2E_* URLs and E2E_RUN=true in Jenkins job environment for :e2e-tests:test'
            }
        }
    }
}
