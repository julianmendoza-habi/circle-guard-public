# Entrega: CI/CD, pruebas y guion de video (CircleGuard)

Este documento resume la implementación, sirve de base para el **informe escrito** y contiene el **guion** del video (máx. 8 minutos) y **dónde capturar pantallazos** para la rúbrica (configuración, resultados, análisis).

## Ámbito técnico

- **Seis microservicios en pipelines:** `auth-service`, `identity-service`, `form-service`, `promotion-service`, `notification-service`, `gateway-service` (comunicación vía HTTP/Kafka/Redis/PostgreSQL/Neo4j según el flujo del dominio).
- **Jenkins (Pipeline as Code):** [`ci/Jenkinsfile.dev.groovy`](../ci/Jenkinsfile.dev.groovy), [`ci/Jenkinsfile.stage.groovy`](../ci/Jenkinsfile.stage.groovy), [`ci/Jenkinsfile.master.groovy`](../ci/Jenkinsfile.master.groovy).
- **Docker:** [`docker/Dockerfile.service`](../docker/Dockerfile.service) (build multi-stage Gradle + JRE 21).
- **Kubernetes:** [`deploy/k8s/`](../deploy/k8s/) (namespaces `circleguard-dev` / `circleguard-stage` / `circleguard-master`, infra y apps).
- **Pruebas:** unitarias e integración (JUnit 5, Testcontainers, WireMock), E2E en [`e2e-tests/`](../e2e-tests/), carga con **Locust** en [`tests/performance/`](../tests/performance/).
- **Release notes:** [`scripts/generate-release-notes.ps1`](../scripts/generate-release-notes.ps1).

### Comandos útiles

| Objetivo | Comando |
|----------|---------|
| Tests rápidos (sin Docker / sin integración) | `./gradlew test` |
| Integración (Docker requerido) | `./gradlew test -Pintegration` |
| E2E (URLs reales) | `E2E_RUN=true` + `E2E_*_URL` + `./gradlew :e2e-tests:test` |
| Locust | `pip install -r tests/performance/requirements-locust.txt` luego `locust -f tests/performance/locustfile.py` (o headless en Jenkins stage) |

---

## Informe por pipeline (texto + qué capturar)

### 1) Pipeline **dev** — [`ci/Jenkinsfile.dev.groovy`](../ci/Jenkinsfile.dev.groovy)

**Configuración (lo que pones en el documento):**

- Fragmento del `stages` (Checkout → Gradle test → Docker build → kubectl apply).
- Cómo enlazas el job a **Pipeline script from SCM** apuntando al repo de GitHub.
- Variables/credenciales: ID de credencial de registry (sin mostrar secretos), `kubectl` / kubeconfig.

**Pantallazos:**

- Jenkins → el job **dev** → *Configure* → sección Pipeline (SCM, rama, path al `Jenkinsfile` o al `.groovy` en `ci/`).
- Blue Ocean o *Stage View* con todas las etapas en verde.
- *Test Result* o publicación de JUnit (`**/build/test-results/test/*.xml`).
- Consola: fragmento de `./gradlew test` exitoso.
- Terminal o Lens: `kubectl get pods,svc -n circleguard-dev` tras el deploy.

**Análisis:** explicar que en dev se prioriza feedback rápido: tests unitarios por defecto (`integration` excluida salvo que se pase `-Pintegration`), imágenes etiquetadas y despliegue a namespace de desarrollo.

---

### 2) Pipeline **stage** — [`ci/Jenkinsfile.stage.groovy`](../ci/Jenkinsfile.stage.groovy)

**Configuración:** etapas con `-Pintegration`, despliegue a `circleguard-stage`, etapa Locust con `--html` y CSV.

**Pantallazos:**

- Job stage → parámetros si los definiste (usuarios Locust, tiempo).
- Artefactos archivados: `locust-report-stage.html`, CSV generados.
- Reporte HTML de Locust abierto en el navegador: gráficas de **RPS**, percentiles de tiempo de respuesta, **% failures**.

**Análisis:** interpretar **latencia p50/p95**, **throughput (RPS)** y **tasa de error**; relacionar cuellos de botella (por ejemplo gateway con tokens inválidos genera muchos 4xx/5xx según configuración).

---

### 3) Pipeline **master** — [`ci/Jenkinsfile.master.groovy`](../ci/Jenkinsfile.master.groovy)

**Configuración:** gates (tests), deploy `circleguard-master`, E2E condicionados a `E2E_RUN`, generación de `RELEASE_NOTES.md` con el script PowerShell.

**Pantallazos:**

- Ejecución exitosa del stage *Release Notes* y artefacto `build/RELEASE_NOTES.md` descargable.
- Contenido del archivo de notas (versión, lista de commits, notas de rollback).
- Opcional: captura de GitHub Release si subes el mismo texto.

**Análisis:** describir **Change Management**: qué entra en la versión, riesgos, plan de **rollback** (`kubectl rollout undo ...`).

---

## Guion de video (≤ 8 minutos)

**Duración orientativa:** 30 s intro + ~2 min dev + ~2 min pruebas + ~1 min stage + ~1.5 min master + 30 s cierre.

### 0:00–0:30 — Introducción

- Nombre del proyecto (CircleGuard), objetivo del trabajo (Jenkins + Docker + K8s + pruebas multinivel).
- Lista los **seis** microservicios incluidos en los pipelines.

### 0:30–2:30 — Pipeline **dev**

- Muestra en el repo los archivos `docker/Dockerfile.service` y `deploy/k8s/apps/dev/microservices.yaml`.
- En Jenkins: job dev, SCM desde GitHub, una ejecución **verde**.
- Muestra brevemente logs de `gradlew test` y el informe de tests.
- Muestra pods/servicios en `circleguard-dev`.

### 2:30–4:30 — Pruebas

- **Unitarias:** ejemplo en IDE o reporte (p. ej. `SymptomMapperBusinessRulesTest`, `JwtTokenServiceClaimsTest`, `TemplateServiceNotificationCopyTest`).
- **Integración:** menciona `@Tag("integration")` y `-Pintegration`; muestra una clase en `.../integration/` y (si tienes Docker) resultado verde.
- **E2E:** módulo `e2e-tests`, variables `E2E_*` y `E2E_RUN=true`.
- **Locust:** abre el HTML del reporte y comenta **RPS**, percentiles y errores.

### 4:30–5:30 — Pipeline **stage**

- Diferencia respecto a dev: tests con integración y pruebas contra cluster stage.
- Pantallazo del artefacto Locust y una métrica que elijas (por ejemplo p95 y error rate).

### 5:30–7:00 — Pipeline **master** y release

- Gates de calidad, despliegue a `circleguard-master`.
- Muestra `RELEASE_NOTES.md` generado por `scripts/generate-release-notes.ps1` y comenta rollback.

### 7:00–8:00 — Cierre

- Conclusión: pipeline repetible, trazabilidad, pruebas alineadas al dominio (Kafka, Redis, HTTP entre servicios).
- Indica dónde está la documentación (`docs/ENTREGA_VIDEO_Y_PANTALLAZOS.md`) y el repo en GitHub.

---

## Checklist de pantallazos (rúbrica)

| Sección del informe | Qué capturar |
|---------------------|----------------|
| Configuración dev | Jenkins SCM, credencial (IDs visibles, no secretos), trozo relevante del `Jenkinsfile` |
| Resultado dev | Stage View / Blue Ocean verde, JUnit |
| Configuración stage | Job stage, parámetros Locust si aplica |
| Resultado stage | Artefactos Locust (HTML + CSV) |
| Análisis carga | Zoom a gráficas RPS / tiempo / fallos en el HTML de Locust |
| Configuración master | Parámetro `RELEASE_VERSION`, stage Release Notes |
| Resultado master | Artefacto `RELEASE_NOTES.md`, rollout exitoso |
| Kubernetes | `kubectl get pods,svc -n circleguard-*` o dashboard |

---

## Notas de alineación con GitHub

El código del pipeline vive **en el repositorio** (GitHub u otro Git). Jenkins ejecuta el pipeline leyendo ese repositorio; no sustituye la necesidad de un Jenkins (u otro CI) según el enunciado del curso.
