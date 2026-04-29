# Jenkins pipelines (CircleGuard)

Declarative pipeline definitions live alongside the code:

| File | Purpose |
|------|---------|
| [`Jenkinsfile.dev.groovy`](Jenkinsfile.dev.groovy) | Dev: Gradle tests, Docker builds, deploy `circleguard-dev` |
| [`Jenkinsfile.stage.groovy`](Jenkinsfile.stage.groovy) | Stage: `-Pintegration`, deploy `circleguard-stage`, Locust HTML/CSV artifacts |
| [`Jenkinsfile.master.groovy`](Jenkinsfile.master.groovy) | Master: deploy `circleguard-master`, optional E2E, release notes |

---

## Configurar el job en Jenkins (Pipeline desde SCM)

### Prerrequisitos en el agente Jenkins

- **JDK 21** y **`./gradlew`** ejecutable (Linux agent típico).
- **Docker** (para los stages que construyen imágenes).
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
  (en master puedes definir el parámetro `RELEASE_VERSION` en el job: *This project is parameterized*).

### Parámetros opcionales (pipeline master)

En el job **master**, marca **This project is parameterized** → **String Parameter**:

- Name: `RELEASE_VERSION`, Default: `1.0.0`  
  El `Jenkinsfile.master.groovy` usa `${params.RELEASE_VERSION}` en release notes.

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
