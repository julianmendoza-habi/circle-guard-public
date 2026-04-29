/**
 * Stage pipeline: Gradle tests, deploy stage namespace, optional Locust performance report.
 *
 * Integration (-Pintegration) only when RUN_INTEGRATION_TESTS is true (parameter or env).
 * The Kubernetes deploy and Locust stages detect their CLIs (`kubectl`, `locust`/`pip`) and skip
 * gracefully if not present on the agent (instead of breaking the build).
 */
pipeline {
    agent any
    parameters {
        booleanParam(
            name: 'RUN_INTEGRATION_TESTS',
            defaultValue: false,
            description: 'Run Gradle -Pintegration (Testcontainers; requires Docker on the agent)',
        )
        booleanParam(
            name: 'SKIP_K8S_DEPLOY',
            defaultValue: false,
            description: 'Saltar despliegue Kubernetes (útil si el agente no tiene kubectl o cluster)',
        )
        booleanParam(
            name: 'SKIP_LOCUST',
            defaultValue: false,
            description: 'Saltar el stage de Locust (útil si el agente no tiene Python/pip)',
        )
    }
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
                script {
                    def skip = env.SKIP_INTEGRATION_TESTS == 'true'
                    def runIntegration =
                        env.RUN_INTEGRATION_TESTS == 'true' || params.RUN_INTEGRATION_TESTS == true
                    if (!skip && runIntegration) {
                        sh './gradlew test --no-daemon -Pintegration'
                    } else {
                        sh './gradlew test --no-daemon'
                    }
                }
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
                    }
                    return hasKubectl
                }
            }
            steps {
                sh 'kubectl apply -f deploy/k8s/apps/stage/microservices.yaml'
            }
        }
        stage('Locust (stage ingress)') {
            when {
                expression {
                    if (params.SKIP_LOCUST == true || env.SKIP_LOCUST == 'true') {
                        echo 'Stage saltado por SKIP_LOCUST=true.'
                        return false
                    }
                    def hasPip = sh(script: 'command -v pip >/dev/null 2>&1 || command -v pip3 >/dev/null 2>&1', returnStatus: true) == 0
                    if (!hasPip) {
                        echo '[WARN] `pip`/`pip3` no disponible en el agente Jenkins. Stage Locust omitido. ' +
                            'Para silenciar este aviso, marca SKIP_LOCUST=true.'
                    }
                    return hasPip
                }
            }
            steps {
                sh '''
                    PIP_BIN=$(command -v pip || command -v pip3)
                    "$PIP_BIN" install -r tests/performance/requirements-locust.txt
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
