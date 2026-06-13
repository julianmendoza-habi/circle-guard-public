# Jenkins pipelines (CircleGuard)

## Jenkins local con Docker

En la raíz del repo hay [`docker-compose.jenkins.yml`](../docker-compose.jenkins.yml) y la imagen se construye desde [`docker/Dockerfile.jenkins`](../docker/Dockerfile.jenkins) (incluye **Docker CLI** y **kubectl**; la imagen oficial no los trae). Primera vez o tras cambiar el Dockerfile:

```bash
docker compose -f docker-compose.jenkins.yml build --no-cache
docker compose -f docker-compose.jenkins.yml up -d
```

- **URL:** http://localhost:8080  
- **Contraseña inicial del administrador:**

  ```bash
  docker exec circleguard-jenkins cat /var/jenkins_home/secrets/initialAdminPassword
  ```

- **Parar:** `docker compose -f docker-compose.jenkins.yml down`  
- Los datos persisten en el volumen Docker `jenkins_home`.

La primera vez Jenkins muestra el asistente de plugins; puedes instalar los sugeridos y crear un usuario admin.

---

Declarative pipeline definitions live alongside the code:

| File | Purpose |
|------|---------|
| [`Jenkinsfile.dev.groovy`](Jenkinsfile.dev.groovy) | Dev: Gradle tests, (opt) SonarQube, Docker build, Trivy scan, deploy `circleguard-dev` |
| [`Jenkinsfile.stage.groovy`](Jenkinsfile.stage.groovy) | Stage: tests + integration, (opt) SonarQube + Quality Gate, Docker build, Trivy scan, deploy `circleguard-stage`, Locust, E2E smoke |
| [`Jenkinsfile.master.groovy`](Jenkinsfile.master.groovy) | Master: semantic version, tests + integration, (opt) SonarQube + Quality Gate, build, Trivy scan, push (Docker Hub/ECR), **manual approval**, deploy, E2E smoke, release notes, git tag |

---

## CI/CD avanzado (15%)

Capacidades añadidas sobre el pipeline base. **Todas degradan con elegancia**: si la herramienta o
credencial no está configurada, la etapa se salta o avisa en vez de romper el build.

### 1. Análisis estático — SonarQube + cobertura (JaCoCo)
- `build.gradle.kts` aplica **JaCoCo** (reporte XML por módulo) y **`org.sonarqube`** en la raíz
  (tarea `sonar` que agrega todo el multi-proyecto). El XML alimenta
  `sonar.coverage.jacoco.xmlReportPaths`.
- Etapas `SonarQube Analysis` (+ `Quality Gate` en stage/master) corren solo con
  **`RUN_SONARQUBE=true`** y usan `withSonarQubeEnv('<SONARQUBE_SERVER>')` → requieren un servidor
  SonarQube en *Manage Jenkins → System* y un webhook hacia Jenkins para el Quality Gate.
- Local: `./gradlew sonar -Dsonar.host.url=... -Dsonar.token=...`.

### 2. Escaneo de imágenes — Trivy
- Etapa `Trivy Image Scan` ([`scripts/ci/trivy-scan.sh`](../scripts/ci/trivy-scan.sh)) escanea las
  imágenes recién construidas. Usa el binario `trivy` del host o, si no, la imagen oficial vía Docker.
  Severidades `HIGH,CRITICAL`, `--ignore-unfixed`.
- **`TRIVY_FAIL_ON_FINDINGS`**: master = `true` (gatea), dev/stage = `false` (solo avisa).
- Complementa el `scan_on_push` que el módulo Terraform `ecr` ya activa.

### 3. Versionado semántico
- [`scripts/ci/semantic-version.sh`](../scripts/ci/semantic-version.sh) calcula la próxima versión
  desde *Conventional Commits* desde el último tag `vX.Y.Z` (`feat!`/`BREAKING CHANGE`→major,
  `feat:`→minor, resto→patch).
- Master: con **`AUTO_VERSION=true`** (default) la usa para taggear imágenes (`prod-<ver>` +
  `prod-latest`), release notes y, con **`PUSH_GIT_TAG=true`**, crea/empuja el tag `v<ver>`.

### 4. Notificaciones
- `post { success/unstable/failure }` en los 3 pipelines llama a `notifyBuild()`: intenta **Slack**
  (`slackSend`, requiere el plugin Slack Notification) y **email** (si `NOTIFY_EMAIL` y hay SMTP).
  Ambos van en try/catch → nunca rompen el build.

### 5. Aprobación manual a prod
- Master: etapa `Approval (prod)` con `input` (timeout 60 min, registra al aprobador) antes de
  desplegar. Desactivable con **`REQUIRE_APPROVAL=false`** para corridas automáticas.

### 6. Registry configurable (Docker Hub / ECR)
- Master: parámetro **`REGISTRY_TYPE`** = `dockerhub` (default) | `ecr`.
  - `dockerhub`: usa `IMAGE_NAMESPACE` + credencial `DOCKERHUB_CREDENTIALS_ID` (igual que antes).
  - `ecr`: `aws ecr get-login-password` con `AWS_CREDENTIALS_ID` (access key/secret), `AWS_REGION`
    y **`ECR_REGISTRY`** (`<account>.dkr.ecr.<region>.amazonaws.com`, del output Terraform
    `terraform/shared` → `ecr_repository_urls`). Repos: `circleguard/<svc>`.
  - ⚠️ Los manifiestos de `master` referencian Docker Hub (`demitard/*`). Para deploy desde ECR,
    apunta las imágenes con `kubectl set image` / kustomize (pendiente, documentado).

### Credenciales nuevas (Manage Jenkins → Credentials)
| ID (default) | Tipo | Uso |
|---|---|---|
| `aws-credentials` | Username+password | Access key id + secret para login ECR (solo `REGISTRY_TYPE=ecr`) |
| SonarQube server + token | config del plugin SonarQube Scanner | Análisis estático (lo gestiona `withSonarQubeEnv`) |
| Slack workspace | config del plugin Slack Notification | `slackSend` en notificaciones |

---

## Configurar el job en Jenkins (Pipeline desde SCM)

### Prerrequisitos en el agente Jenkins

- **JDK 21** y **`./gradlew`** ejecutable (Linux agent típico).
- **Docker** (para construir imágenes y ejecutar Testcontainers con `-Pintegration`).
- **Testcontainers (`-Pintegration`):** se ejecutan siempre en stage/master. Necesitas Docker usable desde el agente (p. ej. [`docker-compose.jenkins.yml`](../docker-compose.jenkins.yml) con `/var/run/docker.sock` y permisos; opción `user: "0:0"` o `group_add` + GID del grupo `docker` del host).
- **`kubectl`** con contexto apuntando a tu cluster (Minikube, Kind, EKS, etc.).
- Plugins recomendados: **Pipeline**, **Git**, **JUnit**, **Credentials Binding**, **Pipeline: Stage View** o **Blue Ocean** (opcional).

### Credenciales en Jenkins (Manage Jenkins → Credentials)

Crea los IDs que uses en los pipelines (o cambia los scripts):

| ID sugerido | Tipo | Uso |
|-------------|------|-----|
| `docker-registry-url` | Secret text / UsernamePassword | URL del registry si haces `docker push` (el `Jenkinsfile.dev` referencia esto; si no tienes registry aún, quita o comenta el bloque `environment` que usa `credentials`). |
| Kubeconfig | Secret file | Archivo kubeconfig para despliegues (úsame con `withCredentials` si amplías el pipeline). |

Para GitHub con HTTPS: usuario + **Personal Access Token** como contraseña, o SSH key si clonas por SSH.

### Crear un job Pipeline (ejemplo: **dev**)

1. **New Item** → nombre: `circle-guard-dev` → **Pipeline** → OK.
2. En **Pipeline**:
   - **Definition:** *Pipeline script from SCM*.
   - **SCM:** Git.
   - **Repository URL:** `https://github.com/TU_USUARIO/circle-guard-public.git` (o SSH).
   - **Credentials:** selecciona las credenciales de Git si el repo es privado.
   - **Branch:** `*/master` (o `develop` si la usas).
   - **Script Path:** `ci/Jenkinsfile.dev.groovy`  
     (Importante: no es `Jenkinsfile` en la raíz; la ruta es exactamente esta.)
3. Guardar → **Build Now**.

Repite con otro nombre y **Script Path:**

- `ci/Jenkinsfile.stage.groovy` → job `circle-guard-stage`
- `ci/Jenkinsfile.master.groovy` → job `circle-guard-master`

### Parámetros del pipeline

La primera vez que Jenkins carga el Groovy desde SCM, registra los parámetros del pipeline. En **master** se mantienen `RELEASE_VERSION`, `IMAGE_NAMESPACE` y `DOCKERHUB_CREDENTIALS_ID`. Los parámetros para saltar integración, build o deploy fueron eliminados: esas etapas son obligatorias.

- `RELEASE_VERSION` (solo master): usado en release notes.

Variables de entorno para E2E: en el job → **Build Environment** → inject:

- `E2E_AUTH_URL`, `E2E_GATEWAY_URL`, `E2E_FORM_URL`, `E2E_PROMOTION_URL`, `E2E_IDENTITY_URL` (URLs del ingress o port-forward).

### Si falla por `credentials('docker-registry-url')`

Ese ID debe existir o hay que editar [`Jenkinsfile.dev.groovy`](Jenkinsfile.dev.groovy) y eliminar o sustituir:

```groovy
environment {
    REGISTRY = credentials('docker-registry-url')
    ...
}
```

por valores fijos o quitar el bloque hasta que tengas registry.

### Multibranch (opcional)

**New Item** → **Multibranch Pipeline** → Branch Sources → Git → misma URL → en **Script Path** pon por ejemplo `ci/Jenkinsfile.dev.groovy`.  
Útil si cada rama debe construirse sola; entonces suele haber un único `Jenkinsfile` por rama o convención de nombres.

---

## Resumen rápido

| Job | Script Path en Jenkins |
|-----|-------------------------|
| Dev | `ci/Jenkinsfile.dev.groovy` |
| Stage | `ci/Jenkinsfile.stage.groovy` |
| Master | `ci/Jenkinsfile.master.groovy` |

Rama recomendada: `master`. Tras el primer push exitoso, las ejecuciones aparecen en Stage View / Blue Ocean con los stages definidos en cada archivo.

---

## Troubleshooting

### `docker: not found` en el stage *Docker Build & Push*

El agente Jenkins no tiene la **CLI de Docker**. Soluciones:

1. Construye Jenkins con `docker/Dockerfile.jenkins` (incluye Docker CLI + kubectl):
   ```bash
   docker compose -f docker-compose.jenkins.yml build --no-cache
   docker compose -f docker-compose.jenkins.yml up -d
   ```
2. Verifica que el socket de Docker esté montado y que el usuario del agente tenga permisos para usarlo.
3. Si `docker` no está disponible, la pipeline falla porque el build de imágenes es obligatorio.

### `kubectl: command not found` o cluster no accesible

Instala `kubectl`, configura `KUBECONFIG` y verifica que `kubectl get --raw=/version` responda desde el agente. Si `kubectl` o el cluster no están disponibles, la pipeline falla porque el deploy es obligatorio.

### `LocalUserRepositoryJdbcIntegrationTest > initializationError` con `DockerClientProviderStrategy`

Esto ocurre cuando las pruebas de integración (Testcontainers) se ejecutan sin Docker disponible. La build de Gradle ahora **excluye a nivel de archivo** las clases de integración cuando no se pasa `-Pintegration` (mira `build.gradle.kts`), porque el filtro por tag de JUnit Platform corre **después** de cargar la clase y los campos `@Container static` ya intentan resolver el cliente Docker.

Los tests de integración corren siempre en stage/master; asegura Docker en el agente (socket montado, ver `docker-compose.jenkins.yml`).
