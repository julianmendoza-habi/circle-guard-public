# Demo Runbook — CircleGuard (video del proyecto)

Guion para grabar la demostración (20–30 min) con el stack corriendo en **Kubernetes local (kind)**.
Cada sección apunta a un ítem de la rúbrica. Comandos pensados para PowerShell en Windows.

> El build JVM nativo no corre en este host (loopback AF_UNIX, ver [HANDOFF.md](../HANDOFF.md) §4);
> todo lo que compila se hace dentro de contenedores Linux (`scripts/verify-local-docker.ps1`,
> `scripts/deploy-local-kind.ps1`).

---

## 0. Antes de grabar

1. **Docker Desktop corriendo** (16 GB asignados recomendado).
2. **Levantar el stack** (idempotente; ~10–15 min la primera vez):
   ```powershell
   ./scripts/deploy-local-kind.ps1
   ```
   Verifica que todo quede Ready:
   ```powershell
   kubectl get pods -A | Select-String circleguard
   ```
   Esperado: 6 pods en `circleguard-dev` (1/1), 6 en `circleguard-infra`, 6 en `circleguard-observability`.
3. **Abrir los port-forwards** en una ventana PowerShell aparte (déjala abierta durante la grabación):
   ```powershell
   ./scripts/demo-port-forwards.ps1
   ```
   URLs: Gateway `:18087`, Auth `:18080`, Identity `:18081`, Promotion `:18088`, Form `:18086`,
   **Grafana** `:3000` (admin/admin), **Prometheus** `:9090`, **Jaeger** `:16686`, Alertmanager `:9093`.

---

## 1. Arquitectura e infraestructura (rúbrica 2, 9)
- Mostrar el diagrama de arquitectura y los diagramas de IaC en
  [terraform/docs/architecture.md](../terraform/docs/architecture.md) y [docs/](.).
- Mostrar el cluster vivo:
  ```powershell
  kubectl get pods -A | Select-String circleguard
  kubectl get svc -n circleguard-dev
  ```
- **IaC (Terraform, 20%)** — sin pagar AWS, mostrar la estructura modular y la validación:
  ```powershell
  # estructura: terraform/{bootstrap,modules,environments/{dev,stage,prod},shared}
  # (con terraform instalado) en environments/dev:  terraform init -backend=false; terraform validate
  ```
  Explicar backend remoto (S3 + DynamoDB lock), módulos (network/eks/rds/redis/msk/ecr) y los 3 ambientes.

## 2. Aplicación funcionando (rúbrica, demo principal)
El flujo central es la **validación de acceso por QR** en el gateway (GREEN/RED según estado de salud).

- **GREEN** (persona sana, sin estado de riesgo):
  ```powershell
  ./scripts/demo-qr-token.ps1 -Call
  # -> valid=True status=GREEN message="Welcome to Campus"
  ```
- **RED** (contacto/contagio — siembra el estado en Redis y revalida):
  ```powershell
  ./scripts/demo-qr-token.ps1 -Call -Status CONTAGIED
  # -> valid=False status=RED message="Access Denied: Health Risk Detected"
  ```
- Mostrar el **vault de anonimización** (identity) y promotion:
  ```powershell
  Invoke-RestMethod http://127.0.0.1:18081/api/v1/identities/map -Method POST -Body '{"realIdentity":"alice@uni.edu"}' -ContentType application/json
  Invoke-RestMethod http://127.0.0.1:18088/api/v1/health-status/stats
  ```

## 3. CI/CD (rúbrica 4, 15%)
- Mostrar los pipelines: [ci/Jenkinsfile.dev.groovy](../ci/Jenkinsfile.dev.groovy) (+ stage/master) y
  [ci/README.md](../ci/README.md): etapas test+cobertura, SonarQube, Trivy, ZAP, versionado semántico,
  notificaciones, aprobación a prod, promoción dev→stage→master.
- Correr etapas clave **en vivo** (contenedor Linux):
  ```powershell
  ./scripts/verify-local-docker.ps1 test jacocoAggregatedReport      # tests + cobertura
  scripts/ci/semantic-version.sh                                      # versionado semántico (Git Bash)
  ```

## 4. Pruebas (rúbrica 5, 15%)
- **Cobertura:** abrir `build/reports/jacoco/aggregate/html/index.html` (generado arriba). ~35% línea base.
- **Pirámide completa** documentada en [docs/TESTING.md](TESTING.md) (unit, integración/Testcontainers,
  E2E, Locust, cobertura, ZAP).
- **Rendimiento (Locust):** con los port-forwards arriba,
  ```powershell
  # genera carga sobre el gateway; mostrar la UI de Locust / resultados
  python -m locust -f tests/performance/locustfile.py --host http://127.0.0.1:18087
  ```
- **Seguridad (ZAP DAST):** `scripts/ci/run-zap-with-kube-port-forward.sh circleguard-dev` (en agente Linux/CI)
  o mostrar el reporte HTML archivado por el pipeline.

## 5. Dashboards de monitoreo (rúbrica 7, 10%)
- **Grafana** → http://127.0.0.1:3000 (admin/admin) → dashboard **"CircleGuard — Overview"**:
  métrica de negocio `circleguard_gate_validations_total` (GREEN/RED), tasa de requests, p95, 5xx,
  heap JVM, targets up. Generar tráfico antes para poblar:
  ```powershell
  1..30 | % { ./scripts/demo-qr-token.ps1 -Call | Out-Null }
  ```
- **Prometheus** → http://127.0.0.1:9090 → `Status > Targets` (6 servicios UP) y consultar
  `circleguard_gate_validations_total`.
- **Jaeger** → http://127.0.0.1:16686 → trazas distribuidas por servicio.
- **Alertmanager** → http://127.0.0.1:9093 → reglas (ServiceDown, Http5xx, latencia p95, GateRedSpike).
- **Health probes:** `kubectl get pods -n circleguard-dev` (liveness/readiness configurados).

## 6. Patrones de diseño (rúbrica 3, 10%)
- **Circuit Breaker (Resilience4j):** port-forward auth y mostrar el estado:
  ```powershell
  kubectl port-forward -n circleguard-dev svc/circleguard-auth-service 18080:8080
  Invoke-RestMethod http://127.0.0.1:18080/actuator/circuitbreakers
  ```
- **Feature Toggle** y catálogo de patrones: [docs/DESIGN_PATTERNS.md](DESIGN_PATTERNS.md).

## 7. Lecciones aprendidas / cierre
- Observabilidad como red de seguridad; resiliencia entre servicios; trade-offs local (kind) vs EKS;
  hallazgo y fix de tests rojos de CI; el bloqueo de loopback resuelto con build en contenedor.

---

## Teardown
```powershell
# detener port-forwards: Enter en la ventana de demo-port-forwards.ps1
kind delete cluster --name circleguard        # borra todo el cluster
```

## Troubleshooting
- **Pod en CrashLoopBackOff:** `kubectl logs <pod> -n <ns> --previous` y `kubectl describe pod <pod> -n <ns>`.
  (Caso conocido ya corregido: actuator protegido por seguridad → probe 401; ver `identity` SecurityConfig.)
- **Imagen no encontrada (ErrImageNeverPull/ImagePullBackOff):** recargar con
  `docker save circleguard/<svc>:dev-latest -o t.tar; kind load image-archive t.tar --name circleguard`.
- **Dashboards vacíos:** generar tráfico (sección 5) y esperar ~15s al scrape de Prometheus.
- **Redeploy de un servicio tras cambio de código:** rebuild + reload + `kubectl rollout restart deploy/circleguard-<svc> -n circleguard-dev`.
