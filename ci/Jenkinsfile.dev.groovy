/**
 * Dev pipeline: `./gradlew test` sin `-Pintegration` (sin Testcontainers).
 *
 * Los stages de Docker y Kubernetes detectan automáticamente si las CLIs (`docker`, `kubectl`)
 * están disponibles en el agente Jenkins. Si no, el stage se omite con un mensaje claro
 * (en lugar de fallar con `command not found`). Para construir imágenes desde Jenkins, usa el
 * `docker/Dockerfile.jenkins` que incluye Docker CLI + kubectl, o monta el socket del host
 * (ver `docker-compose.jenkins.yml`). Para forzar la omisión: `SKIP_DOCKER_BUILD=true` o
 * `SKIP_K8S_DEPLOY=true` (env var de job). El deploy exige además `kubectl get --raw=/version` OK (API real);
 * si el kubeconfig apunta a un HTML de login, el stage se omite.
 *
 * Tras el build Docker se etiqueta `dev-latest` (como en deploy/k8s/apps/dev/microservices.yaml). Si existe el
 * contenedor `circleguard-k3s` (k3s en Docker Compose), las imágenes se importan a containerd con `ctr import`
 * para que el cluster no haga ImagePull de un registry inexistente.
 */
pipeline {
    agent any
    parameters {
        booleanParam(
            name: 'SKIP_DOCKER_BUILD',
            defaultValue: false,
            description: 'Saltar la construcción de imágenes Docker (útil si el agente no tiene docker CLI)',
        )
        booleanParam(
            name: 'SKIP_K8S_DEPLOY',
            defaultValue: false,
            description: 'Saltar despliegue Kubernetes (útil si el agente no tiene kubectl o cluster)',
        )
    }
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
        stage('Docker Build & Push (six services)') {
            when {
                expression {
                    if (params.SKIP_DOCKER_BUILD == true || env.SKIP_DOCKER_BUILD == 'true') {
                        echo 'Stage saltado por SKIP_DOCKER_BUILD=true.'
                        return false
                    }
                    def hasDocker = sh(script: 'command -v docker >/dev/null 2>&1', returnStatus: true) == 0
                    if (!hasDocker) {
                        echo '[WARN] `docker` CLI no disponible en el agente Jenkins. ' +
                            'Stage omitido. Reconstruye Jenkins con `docker/Dockerfile.jenkins` ' +
                            'o monta el socket Docker del host (ver `docker-compose.jenkins.yml`). ' +
                            'Para silenciar este aviso, marca SKIP_DOCKER_BUILD=true.'
                    }
                    return hasDocker
                }
            }
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
                    echo 'Configure docker login + push to your registry before production use.'
                }
            }
        }
        stage('Deploy Kubernetes (dev)') {
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
