/**
 * Dev pipeline: `./gradlew test` sin `-Pintegration` (sin Testcontainers). Docker build usa el demonio
 * del host; si falla, monta docker.sock (ver docker-compose.jenkins.yml). Optional: docker login/push.
 */
pipeline {
    agent any
    environment {
        IMAGE_NAMESPACE = 'circleguard'
        TAG = "${env.GIT_COMMIT.take(7)}-${env.BUILD_NUMBER}"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Gradle Unit Tests') {
            steps {
                sh './gradlew test --no-daemon'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
                }
            }
        }
        stage('Docker Build & Push (six services)') {
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
                    svcs.each { svcDir ->
                        def shortName = svcDir.replaceFirst(/^circleguard-/, '')
                        sh """
                            docker build -f docker/Dockerfile.service \\
                              --build-arg SERVICE_DIR=${svcDir} \\
                              -t ${IMAGE_NAMESPACE}/${shortName}:dev-${TAG} .
                        """
                    }
                    echo 'Configure docker login + push to your registry before production use.'
                }
            }
        }
        stage('Deploy Kubernetes (dev)') {
            steps {
                sh 'kubectl apply -f deploy/k8s/namespaces.yaml'
                sh 'kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml'
                sh 'kubectl apply -f deploy/k8s/infra/kafka-zookeeper.yaml'
                sh 'kubectl apply -f deploy/k8s/infra/openldap.yaml'
                sh 'kubectl apply -f deploy/k8s/apps/dev/microservices.yaml'
                sh 'kubectl rollout status deployment/circleguard-gateway-service -n circleguard-dev --timeout=180s'
            }
        }
    }
}
