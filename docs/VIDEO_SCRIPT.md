# Guion del Video (≤ 12 min) — CircleGuard

Guion cronometrado para el video de sustentación. **Antes de grabar:** stack corriendo
(`./scripts/deploy-local-kind.ps1` ya ejecutado) y port-forwards abiertos
(`./scripts/demo-port-forwards.ps1` en una ventana aparte). Ten pre-abiertas las pestañas:
Grafana `http://localhost:3000` (admin/admin), Prometheus `:9090`, Jaeger `:16686`, y 2 terminales.

> Objetivo: ≤12 min. Habla mientras ejecutas; no leas archivos largos en cámara.

---

### 0:00–1:00 · Intro + arquitectura (1 min)
- "CircleGuard: rastreo de contactos universitario con privacidad por diseño; 8 microservicios
  Spring Boot sobre Kubernetes."
- Mostrar el stack vivo:
  ```powershell
  kubectl get pods -A | Select-String circleguard
  ```
  → "18 pods: 6 microservicios + 6 infra (Postgres/Neo4j/Kafka/Redis/LDAP) + 6 observabilidad."
- Señalar el README/diagrama de arquitectura (1 pantallazo).

### 1:00–2:30 · IaC con Terraform (1.5 min)
- Mostrar el árbol `terraform/` (modules, environments/{dev,stage,prod}, bootstrap, shared).
- "Modular, multi-ambiente, backend remoto S3 + lock DynamoDB." Mostrar `terraform/docs/architecture.md` (diagrama).
- (Opcional si hay terraform) `terraform -chdir=terraform/environments/dev validate`.

### 2:30–5:00 · Aplicación funcionando — demo principal (2.5 min)
- "El núcleo: validación de acceso por QR de salud en el gateway."
- **GREEN** (persona sana):
  ```powershell
  ./scripts/demo-qr-token.ps1 -Call
  ```
  → resaltar `status=GREEN, "Welcome to Campus"`.
- **RED** (riesgo de salud):
  ```powershell
  ./scripts/demo-qr-token.ps1 -Call -Status CONTAGIED
  ```
  → `status=RED, "Access Denied"`. Explicar: el gateway valida el JWT-QR y consulta el estado en Redis.
- Mostrar **anonimización**: `Invoke-RestMethod http://localhost:18081/api/v1/identities/map -Method POST -Body '{"realIdentity":"alice@uni.edu"}' -ContentType application/json` → devuelve `anonymousId` (la PII queda en el vault cifrado).

### 5:00–7:00 · Observabilidad (2 min)
- Generar tráfico rápido para poblar dashboards: `1..20 | % { ./scripts/demo-qr-token.ps1 -Call | Out-Null }`
- **Grafana** → dashboard "CircleGuard — Overview": métrica de negocio (GREEN/RED), tasa de requests, p95, 5xx, heap JVM, targets up.
- **Jaeger** → elegir un servicio → mostrar una traza distribuida.
- **Prometheus** → Status > Targets (6 UP). Mencionar Alertmanager (alertas ServiceDown/5xx/latencia).
- "Health probes liveness/readiness en cada pod."

### 7:00–9:00 · CI/CD + Pruebas (2 min)
- Mostrar `ci/Jenkinsfile.dev.groovy` (stages: test+cobertura, SonarQube, Trivy, build, deploy, ZAP) y
  `Jenkinsfile.master` (aprobación manual a prod). "Promoción dev→stage→master, versionado semántico, notificaciones."
- Cobertura: abrir `build/reports/jacoco/aggregate/html/index.html` (≈35% línea base; gate en 0.30).
- Mencionar pirámide: unit, integración (Testcontainers), E2E, **Locust** (rendimiento), **OWASP ZAP** (DAST). Ver `docs/TESTING.md`.

### 9:00–10:30 · Patrones de diseño + Seguridad (1.5 min)
- **Circuit Breaker (Resilience4j):**
  ```powershell
  kubectl port-forward -n circleguard-dev svc/circleguard-auth-service 18080:8080
  Invoke-RestMethod http://localhost:18080/actuator/circuitbreakers
  ```
  "Circuit Breaker + Retry en los saltos REST; Feature Toggle de canales de notificación."
- **Seguridad:** Trivy + ZAP + SonarQube en CI; RBAC en Kubernetes; secretos en K8s Secrets;
  anonimización cifrada. Ver `docs/SECURITY.md`.

### 10:30–11:30 · Metodología ágil + Change Management (1 min)
- Mostrar `git log --oneline --graph` o las ramas: "GitFlow por ambiente: feature/* → develop → master,
  5 iteraciones (sprints) entregadas." Ver `docs/AGILE_METHODOLOGY.md`.
- Change Management + Release Notes + rollback (`docs/CHANGE_MANAGEMENT.md`, `RELEASE_NOTES.md`).

### 11:30–12:00 · Cierre (30 s)
- Lecciones: observabilidad como red de seguridad; resiliencia entre servicios; build reproducible en
  contenedor; despliegue en Kubernetes local. "Todo el código e IaC en el repositorio."

---

**Tip:** si te pasas de 12 min, recorta IaC (1:00) y Metodología (0:30) — el peso está en app +
observabilidad + CI/CD/pruebas. Toda la documentación está indexada en `docs/README.md`.
