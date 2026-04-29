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
| [`Jenkinsfile.dev.groovy`](Jenkinsfile.dev.groovy) | Dev: Gradle tests, Docker builds, deploy `circleguard-dev` |
| [`Jenkinsfile.stage.groovy`](Jenkinsfile.stage.groovy) | Stage: tests (integration opcional), deploy `circleguard-stage`, Locust HTML/CSV |
| [`Jenkinsfile.master.groovy`](Jenkinsfile.master.groovy) | Master: tests (integration opcional), deploy `circleguard-master`, optional E2E, release notes |

---

## Configurar el job en Jenkins (Pipeline desde SCM)

### Prerrequisitos en el agente Jenkins

- **JDK 21** y **`./gradlew`** ejecutable (Linux agent típico).
- **Docker** (para los stages que construyen imágenes).
- **Testcontainers (`-Pintegration`):** por defecto **no** se ejecutan en Jenkins (evita `DockerClientProviderStrategy` cuando el agente no puede usar Docker). Para activarlas: en el job marca **This project is parameterized** y el booleano **`RUN_INTEGRATION_TESTS`**, o define la variable de entorno `RUN_INTEGRATION_TESTS=true`. Necesitas Docker usable desde el agente (p. ej. [`docker-compose.jenkins.yml`](../docker-compose.jenkins.yml) con `/var/run/docker.sock` y permisos; opción `user: "0:0"` o `group_add` + GID del grupo `docker` del host). Para forzar omisión aunque `RUN_INTEGRATION_TESTS` esté en true: `SKIP_INTEGRATION_TESTS=true`.
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

### Parámetros del pipeline (master y stage)

La primera vez que Jenkins carga el Groovy desde SCM, registra los parámetros del pipeline. En **master**: `RELEASE_VERSION` (string) y **`RUN_INTEGRATION_TESTS`** (boolean, por defecto **false**). En **stage**: **`RUN_INTEGRATION_TESTS`** (boolean, por defecto **false**). Con el valor por defecto, Gradle corre **sin** `-Pintegration`, así el build no depende de Testcontainers/Docker en el agente.

- `RELEASE_VERSION` (solo master): usado en release notes.

Variables de entorno para E2E (si activas el stage): en el job → **Build Environment** → inject:

- `E2E_RUN=true`
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
