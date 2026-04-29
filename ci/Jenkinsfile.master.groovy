/**
 * Master/prod pipeline: gates on tests, deploy prod namespace, smoke E2E, Release Notes artifact.
 */
pipeline {
    agent any
    parameters {
        string(name: 'RELEASE_VERSION', defaultValue: '1.0.0', description: 'Semantic version for release notes')
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
        stage('Integration Tests') {
            when {
                expression {
                    return env.SKIP_INTEGRATION_TESTS != 'true'
                }
            }
            steps {
                sh './gradlew test --no-daemon -Pintegration'
            }
        }
        stage('Deploy Kubernetes (master)') {
            steps {
                sh 'kubectl apply -f deploy/k8s/apps/master/microservices.yaml'
                sh 'kubectl rollout status deployment/circleguard-gateway-service -n circleguard-master --timeout=300s'
            }
        }
        stage('Smoke E2E') {
            when { environment name: 'E2E_RUN', value: 'true' }
            steps {
                sh './gradlew :e2e-tests:test --no-daemon'
            }
        }
        stage('Release Notes') {
            steps {
                sh "powershell -File scripts/generate-release-notes.ps1 -Version ${params.RELEASE_VERSION} -OutputPath build/RELEASE_NOTES.md"
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
