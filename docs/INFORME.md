# Informe — Proyecto Final IngeSoft V: CircleGuard

**Sistema de rastreo de contactos universitario con privacidad por diseño, desplegado como
microservicios en Kubernetes con prácticas de DevOps, seguridad y observabilidad.**

- **Repositorio:** `https://github.com/julianmendoza-habi/circle-guard-public` (rama `master`)
- **Documentación detallada:** [`docs/README.md`](README.md) (índice rúbrica → documento)
- **Fecha:** 2026-06-12 · **Versión:** v1.0.0 ([`RELEASE_NOTES.md`](RELEASE_NOTES.md))

---

## 1. Resumen ejecutivo
CircleGuard identifica grupos de contacto ("círculos") y aplica cercos sanitarios rápidos
preservando el anonimato. Se implementaron **8 microservicios Spring Boot (Java 21)** sobre
Kubernetes, con modelo de datos híbrido (PostgreSQL + Neo4j + Redis), bus de eventos Kafka, un
**vault de anonimización** cifrado y **validación de acceso por QR firmado**. El proyecto cubre los
9 requisitos de la rúbrica, verificados con un despliegue local funcional en Kubernetes (kind) y
pruebas automatizadas.

## 2. Arquitectura
- **Microservicios:** auth (LDAP/local + JWT), identity (vault de anonimización cifrado),
  promotion (motor de promoción de estado sobre grafo Neo4j), notification (multicanal
  push/email/SMS), form (cuestionarios), gateway (validación de acceso por QR), dashboard
  (analítica con K-anonymity), file (almacenamiento). 6 de 8 están en los manifiestos de despliegue.
- **Datos:** PostgreSQL 16 (identidad/config), Neo4j 5.26 (grafo de contactos), Redis 7.2 (caché de
  estado en la ruta caliente del gateway), Kafka 7.6 (eventos/auditoría asíncronos).
- **Flujo de negocio:** login → anonimización (identity) → emisión de token → el gateway valida el
  QR (HMAC) y consulta el estado de salud en Redis → **GREEN** (acceso) o **RED** (denegado).
- Detalle de patrones y privacidad en [`DESIGN_PATTERNS.md`](DESIGN_PATTERNS.md).

## 3. Metodología ágil y branching (Req. 1 — 10%)
**Scrum adaptado**: 5 sprints, cada uno un incremento desplegable en su rama `feature/*`.
**GitFlow por ambiente**: `feature/* → develop → (stage) → master`, alineado a los namespaces
`circleguard-{dev,stage,master}` y a los 3 Jenkinsfiles. Historias de usuario con criterios de
aceptación, Definition of Done y tablero en GitHub. Evidencia: 5 ramas feature, ~60 commits, PRs.
Detalle: [`AGILE_METHODOLOGY.md`](AGILE_METHODOLOGY.md).

## 4. Infraestructura como código (Req. 2 — 20%)
Terraform **modular** (`network`, `eks`, `rds-postgres`, `elasticache-redis`, `msk-kafka`, `ecr`,
`cluster-bootstrap`), **multi-ambiente** (`environments/{dev,stage,prod}`), con **backend remoto**
(S3 versionado/cifrado + lock DynamoDB) y ECR global compartido. Validado con `terraform validate`.
Detalle y diagramas: [`terraform/README.md`](../terraform/README.md),
[`terraform/docs/architecture.md`](../terraform/docs/architecture.md).

## 5. Patrones de diseño (Req. 3 — 10%)
Documentados los existentes (vault/tokenización, cadena JWT+QR, cache-aside, event-driven/pub-sub,
dispatcher multicanal, Strategy, K-anonymity, Builder/Repository) y añadidos dos nuevos:
**Circuit Breaker + Retry (Resilience4j)** en los saltos REST síncronos (auth→identity,
dashboard→promotion) con fallback de degradación, y **Feature Toggle** de canales de notificación.
Detalle: [`DESIGN_PATTERNS.md`](DESIGN_PATTERNS.md).

## 6. CI/CD avanzado (Req. 4 — 15%)
Pipelines Jenkins por ambiente (`ci/Jenkinsfile.{dev,stage,master}.groovy`) con: **SonarQube** +
JaCoCo, **Trivy** (escaneo de imágenes), **versionado semántico**, **notificaciones** Slack/email,
**aprobación manual a producción** (`input`), promoción dev→stage→master y push ECR/DockerHub
configurable. Detalle: [`ci/README.md`](../ci/README.md).

## 7. Pruebas (Req. 5 — 15%)
Pirámide completa: **unitarias** (JUnit5/Mockito), **integración** (Testcontainers), **E2E**,
**rendimiento** (Locust), **DAST** (OWASP ZAP baseline). **Cobertura** con reporte JaCoCo agregado
(`jacocoAggregatedReport`) + gate (`jacocoCoverageVerification`, default 0.30); línea base ≈ **35%**
de líneas (gateway 84%, notification 60%, identity 50%, …). Ejecución automatizada en el pipeline.
Detalle: [`TESTING.md`](TESTING.md). *Hallazgo:* se detectó y corrigió que el stage `test` de CI
estaba rojo (health de mail de Actuator con `@MockBean`), y un crash-loop de identity por probe 401.

## 8. Change Management y Release Notes (Req. 6 — 5%)
Proceso formal de gestión de cambios (Conventional Commits → PR → CI → promoción con aprobación),
**versionado SemVer** con tags Git, **generación automática de release notes**
(`scripts/generate-release-notes.ps1`) y **planes de rollback** por capa (Kubernetes
`rollout undo`, imagen, Terraform, base de datos). Detalle: [`CHANGE_MANAGEMENT.md`](CHANGE_MANAGEMENT.md),
[`RELEASE_NOTES.md`](RELEASE_NOTES.md).

## 9. Observabilidad (Req. 7 — 10%)
Tres pilares + alerting: **métricas** (Actuator + Micrometer/Prometheus + Grafana, dashboard
"CircleGuard — Overview", **métrica de negocio** `circleguard_gate_validations_total`),
**trazas distribuidas** (Micrometer→OTLP→**Jaeger**), **logs** (Loki + Promtail), **alertas**
(Alertmanager: ServiceDown, Http5xx, latencia p95, GateRedSpike) y **health probes**
liveness/readiness. Detalle: [`OBSERVABILITY.md`](OBSERVABILITY.md). *(Se usa Loki/Grafana como
alternativa a ELK para gestión de logs.)*

## 10. Seguridad (Req. 8 — 5%)
Escaneo continuo (Trivy + ZAP + SonarQube en CI), **RBAC** de Kubernetes (least-privilege para
Prometheus/Promtail), contenedores no-root, **gestión de secretos** (K8s Secrets; AWS Secrets
Manager preparado en IaC), **anonimización** cifrada en reposo, y plan de **TLS** en el ingress.
Detalle: [`SECURITY.md`](SECURITY.md).

## 11. Despliegue y operación
Despliegue **reproducible en Kubernetes local (kind)** con `scripts/deploy-local-kind.ps1`
(6 microservicios + infra + observabilidad). Manual de demo/operación:
[`DEMO_RUNBOOK.md`](DEMO_RUNBOOK.md). *Nota técnica:* el build JVM nativo no corre en el host
Windows (loopback AF_UNIX), por lo que la compilación se hace en contenedor Linux
(`scripts/verify-local-docker.ps1`).

## 12. Costos de infraestructura
Estimación por ambiente (AWS) en [`terraform/README.md`](../terraform/README.md): el costo lo
dominan EKS (control plane + nodos), RDS y NAT Gateway. Para dev se usan tamaños mínimos; el
despliegue de la demo es **local (kind)**, con costo cero. Estrategias de ahorro previstas:
spot/scale-to-zero (ver bonos/roadmap).

## 13. Resultados y lecciones aprendidas
- **Resultados:** stack completo desplegado y verificado (gate GREEN/RED, 6 targets en Prometheus,
  trazas en Jaeger, dashboards poblados); suite de pruebas en verde; cobertura ≈35% con gate.
- **Lecciones:** (1) la observabilidad fue clave para diagnosticar fallos (probe 401, health de
  mail); (2) la resiliencia (Circuit Breaker) evita fallos en cascada; (3) un bloqueo de entorno
  (loopback AF_UNIX) se resolvió compilando/desplegando en contenedores Linux; (4) el flujo GitFlow
  por ambiente facilitó iteraciones controladas con quality gates.

---

*Documentación completa e índice por requisito: [`docs/README.md`](README.md). Video de
demostración (≤12 min) adjunto en la entrega.*
