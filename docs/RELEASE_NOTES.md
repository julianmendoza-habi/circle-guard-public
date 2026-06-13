# Release Notes — CircleGuard

Notas de versión del proyecto. Formato SemVer; ver el proceso en
[`CHANGE_MANAGEMENT.md`](CHANGE_MANAGEMENT.md). Generables con
`./scripts/generate-release-notes.ps1 -Version <v>`.

---

## v1.0.0 — 2026-06-12

Primera versión integral: microservicios desplegables en Kubernetes con DevOps, seguridad y
observabilidad completas. Consolida las 5 iteraciones (sprints) del proyecto.

### ✨ Features
- **feat(infra): IaC modular en AWS con Terraform** — módulos `network/eks/rds-postgres/
  elasticache-redis/msk-kafka/ecr`; ambientes `dev/stage/prod`; backend remoto S3 + lock DynamoDB.
- **feat(observability): observabilidad de 3 pilares + alerting** — Actuator + Micrometer/Prometheus,
  Grafana (dashboard "CircleGuard — Overview"), trazas Micrometer→OTLP→Jaeger, logs Loki/Promtail,
  Alertmanager; métrica de negocio `circleguard_gate_validations_total`; probes liveness/readiness.
- **feat(ci): CI/CD avanzado** — SonarQube + JaCoCo, Trivy, versionado semántico, notificaciones
  Slack/email, aprobación manual a producción, promoción dev→stage→master, push ECR/DockerHub.
- **feat(patterns): resiliencia y configuración** — Resilience4j Circuit Breaker + Retry con
  fallback en los saltos REST (auth→identity, dashboard→promotion); Feature Toggle de canales de
  notificación; catálogo en [`DESIGN_PATTERNS.md`](DESIGN_PATTERNS.md).
- **feat(testing): cobertura + DAST** — reporte JaCoCo agregado (`jacocoAggregatedReport`) + gate
  (`jacocoCoverageVerification`); OWASP ZAP baseline integrado al pipeline; [`TESTING.md`](TESTING.md).

### 🐛 Fixes
- **fix(notification): tests de contexto rojos** — el health indicator de mail de Actuator fallaba
  con `@MockBean JavaMailSender` ("Beans must not be empty"); deshabilitado en perfil test.
- **fix(identity): probe 401 / CrashLoopBackOff** — `SecurityConfig` ahora permite `/actuator/**`
  para que kubelet (liveness/readiness) y Prometheus accedan sin autenticación.

### 📚 Docs
- Metodología ágil + branching, Change Management + rollback, observabilidad, patrones, pruebas, y
  runbook de demo/operaciones (`docs/`).

### 🚀 Despliegue
- Reproducible en Kubernetes local (kind) con `scripts/deploy-local-kind.ps1`; demo con
  `scripts/demo-port-forwards.ps1` + `scripts/demo-qr-token.ps1` (ver [`DEMO_RUNBOOK.md`](DEMO_RUNBOOK.md)).
- 6 microservicios desplegados: auth, identity, form, promotion, notification, gateway.

### ⚠️ Notas / limitaciones conocidas
- El build JVM nativo no corre en el host Windows (loopback AF_UNIX); se compila en contenedor Linux.
- `dashboard` y `file` no están en los manifiestos de despliegue (6 de 8 servicios desplegados).
- Cobertura de líneas agregada ≈ 35% (línea base; gate en 0.30).

---

> Para generar las notas de la próxima versión: etiquetar `vX.Y.Z` y correr
> `./scripts/generate-release-notes.ps1 -Version X.Y.Z` (recoge los commits desde el último tag).
