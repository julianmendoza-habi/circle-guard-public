# Documentación del Proyecto — IngeSoft V (CircleGuard)

Índice de la documentación de entrega, mapeada a la rúbrica. Punto de partida para evaluar y para la
presentación.

## Mapa rúbrica → documento

| # | Requisito (peso) | Documento / evidencia |
|---|---|---|
| 1 | Metodología ágil + branching (10%) | [AGILE_METHODOLOGY.md](AGILE_METHODOLOGY.md) |
| 2 | IaC con Terraform (20%) | [`terraform/README.md`](../terraform/README.md), [`terraform/docs/architecture.md`](../terraform/docs/architecture.md) |
| 3 | Patrones de diseño (10%) | [DESIGN_PATTERNS.md](DESIGN_PATTERNS.md) |
| 4 | CI/CD avanzado (15%) | [`ci/README.md`](../ci/README.md), [`ci/Jenkinsfile.{dev,stage,master}.groovy`](../ci/) |
| 5 | Pruebas completas (15%) | [TESTING.md](TESTING.md) |
| 6 | Change Management + Release Notes (5%) | [CHANGE_MANAGEMENT.md](CHANGE_MANAGEMENT.md), [RELEASE_NOTES.md](RELEASE_NOTES.md) |
| 7 | Observabilidad (10%) | [OBSERVABILITY.md](OBSERVABILITY.md) |
| 8 | Seguridad (5%) | [SECURITY.md](SECURITY.md) (Trivy/ZAP, RBAC, secretos, TLS) |
| 9 | Documentación + presentación (10%) | Este índice, [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md), diagramas |

## Operación y demo
- **Desplegar local (kind):** [`scripts/deploy-local-kind.ps1`](../scripts/deploy-local-kind.ps1)
- **Demo / video:** [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md) (+ `scripts/demo-port-forwards.ps1`, `scripts/demo-qr-token.ps1`)
- **Verificar build/tests local (contenedor):** [`scripts/verify-local-docker.ps1`](../scripts/verify-local-docker.ps1)
- **Estado del trabajo entre sesiones:** [`HANDOFF.md`](../HANDOFF.md)

## Arquitectura (resumen)
8 microservicios Spring Boot (Java 21) sobre Kubernetes, con modelo de datos híbrido
(PostgreSQL + Neo4j + Redis), bus de eventos Kafka, vault de anonimización, y validación de acceso
por QR firmado. Ver el [README raíz](../README.md) para el detalle de producto y stack.
