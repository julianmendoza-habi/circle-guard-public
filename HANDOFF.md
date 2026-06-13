# Handoff — Proyecto Final IngeSoft V (CircleGuard)

Documento de traspaso para continuar el trabajo. Resume lo hecho, el estado del repo y
el siguiente foco recomendado con puntos de entrada concretos.

_Última actualización: 2026-06-12._

---

## ⭐ START HERE — estado para la próxima sesión

**Rama actual:** `feature/testing-coverage` (creada desde `feature/design-patterns`).

> ✅ **CI/CD avanzado** commiteado/pusheado (`68bbf7e`). ✅ **Patrones de diseño** commiteado por el
> usuario en `feature/design-patterns` (ver §0.1). (`gh` no está instalado → PRs desde la web de GitHub.)

**Esta iteración (Pruebas completas, 15%) está en el working tree pero SIN commitear.** Lo primero:
revisar `git status` + `git diff feature/design-patterns`, `git add -A` y commitear (p. ej.
`feat(testing): cobertura JaCoCo agregada + gate y OWASP ZAP DAST`), luego push + PR.

Cambios de esta iteración (ver detalle en §0.2):
- **Cobertura:** tareas raíz `jacocoAggregatedReport` (XML+HTML combinado) y `jacocoCoverageVerification`
  (gate `-PcoverageMin`, default 0.30) en `build.gradle.kts` (plugin `jacoco` aplicado al root).
- **OWASP ZAP DAST:** `scripts/ci/zap-baseline.sh` + `run-zap-with-kube-port-forward.sh` + `.zap/rules.tsv`;
  stage "OWASP ZAP DAST (dev)" en `Jenkinsfile.dev` (param `RUN_ZAP`, warn-only) + cobertura en el stage
  de tests (params `ENFORCE_COVERAGE`/`COVERAGE_MIN`).
- **Fix CI (importante):** `NotificationRetryTest` y `ExposureNotificationListenerTest` fallaban al cargar
  contexto (`mailHealthContributor` "Beans must not be empty" al mockear `JavaMailSender`) → el stage
  `test` de CI estaba **rojo**. Arreglado deshabilitando el health de mail solo en perfil test
  (`notification .../src/test/resources/application-test.yml`).
- **Docs:** `docs/TESTING.md` (pirámide completa + comandos + integración CI).

**✅ Verificado localmente** vía contenedor Linux (§4): suite unitario completo **verde** en los 8
servicios; `jacocoAggregatedReport` + `jacocoCoverageVerification` (gate 0.30) pasan. **Cobertura de
líneas agregada ≈ 35%** (gateway 84%, notification 60%, identity 50%, form 43%, auth 42%, promotion
26%, file 21%, dashboard 16%). ZAP: scripts `bash -n` OK + Jenkinsfile balanceado; el runtime de ZAP
(`--network host`) se valida en CI (no reproducible en Docker Desktop Windows).

**🟢 DESPLIEGUE LOCAL VIVO (para el video):** stack completo corriendo en **kind** (cluster
`circleguard`): 6 microservicios + 6 infra (postgres/redis/neo4j/kafka/zookeeper/openldap) + 6
observabilidad (Prometheus/Grafana/Jaeger/Loki/Promtail/Alertmanager). Verificado: gate GREEN+RED,
Prometheus scrapea 6 targets, métrica de negocio puebla, Grafana (admin/admin) dashboard
"CircleGuard — Overview", Jaeger con trazas de los 6 servicios. Reproducible con
[`scripts/deploy-local-kind.ps1`](scripts/deploy-local-kind.ps1); demo con
[`scripts/demo-port-forwards.ps1`](scripts/demo-port-forwards.ps1) +
[`scripts/demo-qr-token.ps1`](scripts/demo-qr-token.ps1) siguiendo
[`docs/DEMO_RUNBOOK.md`](docs/DEMO_RUNBOOK.md). **Fix aplicado** (sin commitear): identity
`SecurityConfig` ahora permite `/actuator/**` (el probe daba 401 → crash-loop). El K8s de Docker
Desktop **no** arranca headless (necesita el toggle de la GUI); por eso se usó kind, que es el mismo
motor. `kind load docker-image` falla con el containerd store → usar `kind load image-archive`.

**Siguiente foco recomendado (elegir uno):** Metodología ágil + branching (10%, solo docs) o
Seguridad (5%). Ver §3. (Pruebas, Patrones, CI/CD y Observabilidad — ya hechas.)

**Pendiente menor independiente:** dejar de trackear `services/*/bin` (artefactos de build);
subir cobertura de promotion/file/dashboard.

---

## 0. Observabilidad — COMPLETA (mergeada en `a355ff1`)

Foco 10% cerrado al 100%: **3 pilares (métricas, trazas, logs) + alerting**. Código **mergeado**
(`a355ff1`); el doc `docs/OBSERVABILITY.md` quedó sin trackear y se re-incluye en la rama de CI/CD
(ver START HERE).

- **Instrumentación (8 servicios):** `actuator` + `micrometer-registry-prometheus` +
  `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`, añadidos **una sola vez** en el
  bloque `subprojects` de [`build.gradle.kts`](build.gradle.kts). Cada `application.yml` expone
  `health,info,prometheus,metrics`, probes liveness/readiness, tag `application`, sampling de trazas
  1.0 y endpoint OTLP.
- **Métrica de negocio:** el gateway incrementa
  `circleguard_gate_validations_total{result="green|red"}` en cada validación de QR
  ([`QrValidationService`](services/circleguard-gateway-service/src/main/java/com/circleguard/gateway/service/QrValidationService.java));
  test unitario actualizado (`SimpleMeterRegistry`).
- **Trazas:** clientes REST `IdentityClient` (auth→identity) y `PromotionClient`
  (dashboard→promotion) migrados de `new RestTemplate()` a bean vía `RestTemplateBuilder` →
  propagan `traceparent`. Exportan OTLP a Jaeger.
- **Manifiestos (dev/stage/master):** anotaciones `prometheus.io/scrape|port|path`, probes
  `/actuator/health/*`, y env `MANAGEMENT_OTLP_TRACING_ENDPOINT` (Jaeger) en cada ConfigMap
  `circleguard-runtime-*`.
- **Stack** ([`deploy/k8s/infra/observability.yaml`](deploy/k8s/infra/observability.yaml) +
  [`logging.yaml`](deploy/k8s/infra/logging.yaml), namespace `circleguard-observability`):
  Prometheus (SD por anotaciones + `alerts.yml`), Grafana (datasources Prometheus/Jaeger/Loki +
  dashboard provisionado), Jaeger all-in-one (OTLP), Loki + Promtail (DaemonSet), Alertmanager.
  Storage `emptyDir` (demo).
- **Alertas:** `ServiceDown`, `HighHttp5xxRate`, `HighHttpLatencyP95`, `GateRedValidationSpike`.
- **Docs:** [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) (arquitectura Mermaid de los 4
  componentes, despliegue, panel→PromQL, correlación logs↔trazas, alertas, roadmap).
- ⚠️ **No compilable localmente** (loopback JVM, §4); validado: YAML de los 3 manifiestos de apps
  (14 docs c/u) + `observability.yaml` (16 docs) + `logging.yaml` (8 docs) + todos los configs
  embebidos (prometheus/alerts/loki/promtail/alertmanager + dashboard JSON) parsean OK.

---

## 0.1 Patrones de diseño — COMPLETA (sin commitear, en `feature/design-patterns`)

Foco 10% cerrado. Doc principal: [`docs/DESIGN_PATTERNS.md`](docs/DESIGN_PATTERNS.md) (catálogo de
patrones existentes documentados + los dos nuevos).

- **Circuit Breaker + Retry (Resilience4j)** en los 2 saltos REST síncronos:
  - [`IdentityClient`](services/circleguard-auth-service/src/main/java/com/circleguard/auth/client/IdentityClient.java)
    (auth→identity) — fallback *fail-fast* con
    [`IdentityServiceUnavailableException`](services/circleguard-auth-service/src/main/java/com/circleguard/auth/client/IdentityServiceUnavailableException.java)
    (anonymousId es obligatorio para emitir token → HTTP 500 en `LoginController`).
  - [`PromotionClient`](services/circleguard-dashboard-service/src/main/java/com/circleguard/dashboard/client/PromotionClient.java)
    (dashboard→promotion) — fallback *graceful* (payload centinela; reemplaza el `try/catch` previo).
  - Deps `resilience4j-spring-boot3:2.2.0` + `spring-boot-starter-aop` en cada `build.gradle.kts`.
  - Config `resilience4j.{circuitbreaker,retry}` + actuator `circuitbreakers` + health indicator en
    cada `application.yml`. Las métricas CB salen por el registro Prometheus ya existente (§0).
  - `fallbackMethod` en `@Retry` (aspecto externo) para que dispare solo tras agotar reintentos o con
    circuito OPEN.
- **Feature Toggle** (config externa) en notification-service:
  [`NotificationFeatureProperties`](services/circleguard-notification-service/src/main/java/com/circleguard/notification/config/NotificationFeatureProperties.java)
  (`circleguard.features.channels.{email,sms,push}`, default ON/opt-out) consumido por
  [`NotificationDispatcher`](services/circleguard-notification-service/src/main/java/com/circleguard/notification/service/NotificationDispatcher.java)
  — un canal apagado se omite (ni se renderiza su plantilla). Overridable por env
  (`CIRCLEGUARD_FEATURES_CHANNELS_SMS=false`).
- **Tests** (unitarios, sin proxy AOP — validan el contrato de degradación/toggle):
  `IdentityClientFallbackTest`, `PromotionClientFallbackTest`, `NotificationDispatcherFeatureToggleTest`.
- ✅ **Compilado y testeado localmente** vía contenedor Docker (§4): los 3 módulos compilan y los 3
  tests nuevos pasan (`IdentityClientFallbackTest`, `PromotionClientFallbackTest`,
  `NotificationDispatcherFeatureToggleTest` — 4 tests, 0 fallos). El `NotificationDispatcherTest`
  preexistente sigue válido (todos los canales default ON).

---

## 0.2 Pruebas completas — COMPLETA (sin commitear, en `feature/testing-coverage`)

Foco 15% cerrado: cierra los dos gaps del proyecto (**cobertura** + **OWASP ZAP**). Doc principal:
[`docs/TESTING.md`](docs/TESTING.md) (pirámide completa + comandos + integración CI).

- **Cobertura JaCoCo agregada + gate** ([`build.gradle.kts`](build.gradle.kts)): plugin `jacoco`
  aplicado al root; `jacocoAggregatedReport` fusiona la execution data de los 8 servicios en un XML+HTML
  único (`build/reports/jacoco/aggregate/`, excluye Application/config/dto/model/entity);
  `jacocoCoverageVerification` falla bajo `-PcoverageMin` (default 0.30). Base medida ≈ **35%**.
- **OWASP ZAP baseline (DAST)**: [`scripts/ci/zap-baseline.sh`](scripts/ci/zap-baseline.sh) (scan pasivo,
  reportes HTML/JSON/MD, warn-only por defecto, degrada si no hay Docker),
  [`run-zap-with-kube-port-forward.sh`](scripts/ci/run-zap-with-kube-port-forward.sh) (port-forward del
  gateway), [`.zap/rules.tsv`](.zap/rules.tsv) (tuning).
- **CI** ([`ci/Jenkinsfile.dev.groovy`](ci/Jenkinsfile.dev.groovy)): stage de tests ahora corre
  `jacocoAggregatedReport` y archiva el reporte (gate opcional `ENFORCE_COVERAGE`/`COVERAGE_MIN`); nuevo
  stage "OWASP ZAP DAST (dev)" tras el deploy (param `RUN_ZAP`, `ZAP_FAIL_ON_FINDINGS`).
- **Fix CI rojo:** `NotificationRetryTest` + `ExposureNotificationListenerTest` fallaban al cargar
  contexto (`mailHealthContributor` "Beans must not be empty" con `@MockBean JavaMailSender`); resuelto
  deshabilitando el health de mail en perfil test
  ([`application-test.yml`](services/circleguard-notification-service/src/test/resources/application-test.yml)).
- ✅ **Verificado local** (Docker, §4): suite unitario completo verde; reporte + gate (0.30) pasan.
  ⚠️ El runtime de ZAP (`--network host` + port-forward) se valida en CI Linux, no en Docker Desktop Windows.

---

## 1. Qué ya está hecho (iteración previa)

### Servicio Gateway restaurado
- Los 7 archivos estaban intactos en git `HEAD`; se restauraron con
  `git checkout HEAD -- services/circleguard-gateway-service/src`.
- Contrato: `POST /api/v1/gate/validate` con `{ "token": "<jwt>" }` → valida QR (JWT
  `qr.secret`), consulta Redis `user:status:<anonymousId>`; `CONTAGIED`/`POTENTIAL` → RED,
  si no GREEN. Coincide con Locust/E2E/CI.
- ⚠️ **No se pudo compilar localmente** (ver §4, problema de loopback de la JVM). La CI
  Linux lo compila bien.

### Terraform IaC en AWS (foco 20%) — **validado**
Árbol modular en [`terraform/`](terraform/):
- `bootstrap/` → backend remoto (S3 versionado/cifrado + tabla DynamoDB de lock).
- `modules/` → `network`, `eks` (+IRSA, addon EBS-CSI), `rds-postgres`,
  `elasticache-redis`, `msk-kafka` (opcional), `ecr`, `cluster-bootstrap`.
- `environments/{dev,stage,prod}/` → directorio por entorno, backend key propio, matriz de
  tamaños por costo. `shared/` → repos ECR (globales por cuenta).
- Verificado con Terraform 1.9.8 portable: `validate` OK en los 5 roots; `fmt` sin cambios.

### Migración de manifiestos
- Las 12 ocurrencias de `SPRING_DATASOURCE_URL` (4 servicios × dev/stage/master) ahora
  construyen la URL desde `$(POSTGRES_HOST)`, con un env `POSTGRES_HOST` explícito desde el
  ConfigMap (porque `$()` de Kubernetes **no** ve variables de `envFrom`).

### Docs
- [`terraform/README.md`](terraform/README.md) (orden de apply, costos, validación) y
  [`terraform/docs/architecture.md`](terraform/docs/architecture.md) (diagramas Mermaid).

> **Nada está commiteado todavía.** Cambios pendientes en git: `terraform/` (nuevo),
> `deploy/k8s/apps/{dev,stage,master}/microservices.yaml` (modificados), gateway restaurado.

---

## 2. Estado del repo vs. requisitos del proyecto

| Área (peso) | Estado |
|---|---|
| Metodología ágil + branching (10%) | ❌ Falta documentación (sprints, historias, estrategia de ramas) |
| **Terraform IaC (20%)** | ✅ **Hecho esta iteración** |
| **Patrones de diseño (10%)** | ✅ **Hecho esta iteración**: Circuit Breaker+Retry (Resilience4j) en clientes REST, Feature Toggle de canales, y `docs/DESIGN_PATTERNS.md` (existentes + nuevos) |
| **CI/CD avanzado (15%)** | ✅ **Hecho esta iteración**: SonarQube+JaCoCo, Trivy, versionado semántico, notificaciones, aprobación manual a prod, push ECR/DockerHub configurable |
| **Pruebas completas (15%)** | ✅ **Hecho esta iteración**: cobertura JaCoCo agregada + gate, OWASP ZAP DAST en CI, fix de tests rojos, `docs/TESTING.md`. (Unit/Integration/E2E/Locust ya existían) |
| Change management + release notes (5%) | ⚠️ Script `generate-release-notes.ps1` existe; falta proceso formal y rollback |
| **Observabilidad (10%)** | ✅ **Completa esta iteración**: métricas (Actuator+Prometheus+Grafana, métrica de negocio, probes), trazas (Micrometer→OTLP→Jaeger), logs (Loki+Promtail) y alerting (reglas+Alertmanager) |
| Seguridad (5%) | ⚠️ Secretos en claro; faltan RBAC, TLS, gestión de secretos |
| Documentación + presentación (10%) | ⚠️ Parcial (`docs-entrega/` con Taller 2.pdf + video) |
| Bonos (multi-cloud, service mesh, chaos, FinOps) | ❌ |

---

## 3. Siguiente foco recomendado

Ordenado por relación valor/esfuerzo. Elegir uno por iteración.

### Opción A — Observabilidad (10%) — ✅ COMPLETA
- ✅ Métricas, trazas (Jaeger), logs (Loki/Promtail) y alerting (Alertmanager). Ver §0 y
  [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md).
- Follow-ups opcionales (no requeridos): dashboards RED/USE por servicio, exemplars de trazas,
  receiver real de Slack/email, blackbox-exporter. Migrar `emptyDir`→PVC `gp3` en prod
  (addon EBS-CSI ya presente en el módulo `eks`).

### Opción B — CI/CD avanzado (15%) — ✅ HECHO
- ✅ SonarQube (plugin `org.sonarqube` + JaCoCo en `build.gradle.kts`; etapas con Quality Gate
  guardadas por `RUN_SONARQUBE`), Trivy ([`scripts/ci/trivy-scan.sh`](scripts/ci/trivy-scan.sh)),
  versionado semántico ([`scripts/ci/semantic-version.sh`](scripts/ci/semantic-version.sh)),
  notificaciones (Slack/email en `post`), aprobación manual a prod (`input`), y push
  **ECR/DockerHub configurable** (`REGISTRY_TYPE`). Todo degrada con elegancia.
- Docu: sección "CI/CD avanzado" en [`ci/README.md`](ci/README.md).
- ⏭️ Pendiente menor: los manifiestos de `master` apuntan a Docker Hub (`demitard/*`); para deploy
  desde ECR falta override de imagen (kustomize / `kubectl set image`).
- ⚠️ No verificable localmente (loopback JVM): el plugin Sonar/JaCoCo y los pipelines se validan en
  CI Linux. Validado local: `semantic-version.sh` corre OK, balance de llaves de los 3 Jenkinsfiles,
  Gradle 8.14 (≥ requisito Sonar 5.x).

### Opción C — Patrones de diseño (10%) — ✅ HECHO
- ✅ Resiliencia: Resilience4j (Circuit Breaker + Retry + fallback) en auth→identity y
  dashboard→promotion. Configuración: Feature Toggle (config externa) de canales de notificación.
  Documentado en [`docs/DESIGN_PATTERNS.md`](docs/DESIGN_PATTERNS.md) (patrones existentes — JWT
  chain, vault de anonimización, cascada event-driven, K-anonymity, Spring Retry… — + los nuevos).
  Detalle en §0.1.
- ⏭️ Follow-ups opcionales (no requeridos): `@TimeLimiter`+`@Bulkhead`, panel Grafana de CB,
  flag store centralizado (Unleash).

### Opción D — Seguridad (5%) — sinergia con la IaC
- Reemplazar secretos en claro de los manifiestos por **Secrets Manager** (el módulo
  `rds-postgres` ya crea el secret) o sealed-secrets; activar TLS en ElastiCache/MSK
  (variables ya previstas en los módulos); añadir RBAC de Kubernetes.

---

## 4. Gotchas / cosas que saber

- **Build JVM local roto (con workaround):** el `./gradlew` *nativo* falla aquí con
  `Unable to establish loopback connection` — el `Selector.open()` de la JVM en Windows usa un
  socket-pair **AF_UNIX** que el software de seguridad bloquea (el TCP loopback normal sí funciona).
  Es problema del entorno, no del código. **Workaround:** correr Gradle **dentro de Linux**
  (contenedor Docker, donde AF_UNIX funciona) — usar [`scripts/verify-local-docker.ps1`](scripts/verify-local-docker.ps1)
  (requiere Docker Desktop arriba; imagen `gradle:8.14-jdk21`, que además trae el **JDK 21** del
  toolchain, ya que el PATH tiene Java 17). Ej.:
  `./scripts/verify-local-docker.ps1 :services:circleguard-auth-service:test --tests "*FallbackTest"`.
  Alternativa: WSL2 Ubuntu-24.04 (instalando JDK 21). La CI Linux sigue siendo la fuente de verdad.
- **Terraform no está instalado:** se usó un binario portable (`terraform 1.9.8`) bajado a
  temp para validar. Para `apply` real se necesita una cuenta AWS + credenciales.
- **prod == "master":** el entorno `terraform/environments/prod/` usa `environment = "master"`
  para que el ConfigMap/namespace coincidan con los manifiestos existentes
  (`circleguard-master`, `circleguard-runtime-master`).
- **ECR es global:** está en `terraform/shared/`, no por entorno (los 3 entornos comparten
  los mismos nombres de imagen, diferenciados por tag `dev-/stage-/prod-latest`).
- **Antes de `terraform init` real:** reemplazar `REPLACE_WITH_ACCOUNT_ID` en todos los
  `backend.tf` con el output del `bootstrap`.
- **Servicios sin desplegar en manifiestos:** `dashboard` y `file` no están en
  `deploy/k8s/apps/*` (solo 6 de 8: auth, identity, form, promotion, notification, gateway).

---

## 5. Plan original

El plan aprobado de esta iteración está en
`C:\Users\julianmendoza_habi\.claude\plans\proyecto-final-ingesoft-v-soft-meerkat.md`.
