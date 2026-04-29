/**
 * Master/prod pipeline: gates on tests, deploy prod namespace, smoke E2E, Release Notes artifact.
 *
 * Integration tests (Testcontainers) run only when RUN_INTEGRATION_TESTS is enabled (job parameter or
 * env) and the Jenkins agent can use the host Docker socket — otherwise they fail inside Jenkins.
 *
 * The Kubernetes deploy stage detects whether `kubectl` is available on the agent. If not, the stage
 * is skipped with a clear warning instead of breaking the build. To force-skip, set
 * SKIP_K8S_DEPLOY=true as a job env var.
 */
pipeline {
    agent any
    parameters {
        string(name: 'RELEASE_VERSION', defaultValue: '1.0.0', description: 'Semantic version for release notes')
        booleanParam(
            name: 'RUN_INTEGRATION_TESTS',
            defaultValue: false,
            description: 'Run Gradle -Pintegration (Testcontainers; requires Docker usable from the agent)',
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
                sh './gradlew test --no-daemon -Pintegration'
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
                    }
                    return hasKubectl
                }
            }
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
