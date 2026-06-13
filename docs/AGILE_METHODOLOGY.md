# Metodología Ágil y Estrategia de Branching — CircleGuard

Documenta la metodología ágil aplicada, la estrategia de ramas, el tablero de gestión, y las
iteraciones (sprints) realizadas con sus historias de usuario y criterios de aceptación.
(Rúbrica 1, 10%.)

---

## 1. Metodología: Scrum adaptado (sprints por iteración)

Se trabajó con **Scrum** adaptado a un equipo pequeño, con **sprints cortos de 1 iteración = 1
incremento entregable**. Cada sprint produce un incremento potencialmente desplegable, integrado a
`develop` mediante una rama `feature/*` y su Pull Request.

- **Roles:** Product Owner (prioriza el backlog según la rúbrica/valor), Dev Team (implementa),
  Scrum Master (remueve impedimentos y vela por los quality gates).
- **Ceremonias:** planning (selección del foco de la iteración por relación valor/esfuerzo),
  seguimiento continuo, review (demo del incremento), retro (lecciones → siguiente iteración; ver §5).
- **Artefactos:** Product Backlog (historias, §4), Sprint Backlog (foco de la iteración) e
  Incremento (rama `feature/*` mergeada + demo). El historial de ramas, commits y PRs en GitHub es la
  traza auditable del avance.

> **Por qué Scrum y no Kanban puro:** el trabajo se entregó en incrementos discretos y bien acotados
> (un foco de la rúbrica por iteración), que mapean naturalmente a sprints. El backlog se gestiona
> al estilo Kanban (flujo continuo, WIP=1 iteración) dentro de cada sprint.

## 2. Gestión del proyecto (tablero)

Se usa **GitHub** como sistema de gestión: el historial de **ramas, commits y Pull Requests** es la
traza auditable del backlog y su avance (64 commits, 5 ramas `feature/*`). El tablero recomendado es
**GitHub Projects** con columnas *Backlog → Sprint → In Progress → Review → Done*, una tarjeta por
historia (§4) enlazada a su rama/PR.

## 3. Estrategia de branching: GitFlow por ambiente

Se adoptó **GitFlow adaptado**, con promoción controlada por ambiente (alineada a los 3 Jenkinsfiles
y a los namespaces `circleguard-{dev,stage,master}`):

```
feature/*  ──►  develop  ──►  (stage)  ──►  master
   │             │              │             │
 trabajo      ambiente        ambiente      ambiente
 aislado       DEV             STAGE         PROD (master)
```

| Rama | Propósito | Ambiente / Pipeline |
|---|---|---|
| `feature/*` | Una historia/iteración aislada; PR a `develop` | CI de validación (test+cobertura) |
| `develop` | Integración continua | `circleguard-dev` · [`Jenkinsfile.dev`](../ci/Jenkinsfile.dev.groovy) |
| `stage` | Pre-producción / QA | `circleguard-stage` · [`Jenkinsfile.stage`](../ci/Jenkinsfile.stage.groovy) |
| `master` | Producción (con **aprobación manual**) | `circleguard-master` · [`Jenkinsfile.master`](../ci/Jenkinsfile.master.groovy) |
| `main` | Default del repositorio (mirror estable) | — |

**Reglas:** ramas `feature/<área>` cortas; PR obligatorio para mergear a `develop`; commits estilo
**Conventional Commits** (`feat(...)`, `fix(...)`) que alimentan el versionado semántico y las
release notes; promoción `develop → stage → master` con quality gates (SonarQube, Trivy, cobertura)
y **aprobación manual** antes de prod.

## 4. Product Backlog — Historias de usuario y criterios de aceptación

Historias derivadas de la rúbrica (formato *Como… quiero… para…*). Cada una se entregó en una
iteración (§5).

- **HU-01 — IaC en AWS.** *Como* DevOps, *quiero* la infraestructura como código modular y
  multi-ambiente, *para* recrearla de forma reproducible.
  - *Criterios:* módulos reutilizables (network/eks/rds/redis/ecr); ambientes dev/stage/prod;
    backend remoto (S3+DynamoDB); `terraform validate` OK en los roots.
- **HU-02 — Observabilidad.** *Como* operador, *quiero* métricas, trazas, logs y alertas, *para*
  diagnosticar el sistema.
  - *Criterios:* Prometheus+Grafana con dashboard; trazas en Jaeger; logs en Loki; alertas en
    Alertmanager; health probes; una métrica de negocio.
- **HU-03 — CI/CD avanzado.** *Como* equipo, *quiero* pipelines con calidad y seguridad, *para*
  entregar con confianza.
  - *Criterios:* SonarQube + JaCoCo; Trivy; versionado semántico; notificaciones; aprobación a prod;
    promoción dev→stage→master.
- **HU-04 — Patrones de diseño.** *Como* arquitecto, *quiero* resiliencia y configuración dinámica,
  *para* tolerar fallos y operar sin redeploy.
  - *Criterios:* Circuit Breaker + Retry en llamadas REST; Feature Toggle; documentación de patrones.
- **HU-05 — Pruebas completas.** *Como* QA, *quiero* cobertura y pruebas de seguridad, *para*
  garantizar calidad.
  - *Criterios:* unit/integración/E2E/Locust; cobertura JaCoCo agregada + gate; OWASP ZAP en CI.
- **HU-06 — Validación de acceso (negocio).** *Como* portero del campus, *quiero* validar un QR de
  salud, *para* permitir o denegar el acceso.
  - *Criterios:* `POST /api/v1/gate/validate` retorna GREEN si el `anonymousId` no tiene estado de
    riesgo y RED si está CONTAGIED/POTENTIAL; identidad anonimizada (vault).

## 5. Iteraciones realizadas (≥2 sprints)

Se completaron **cinco iteraciones**, cada una en su rama y con incremento desplegable (cumple y
supera el mínimo de 2):

| Sprint | Rama | Incremento | Historia |
|---|---|---|---|
| 1 | `feature/terraform-aws-iac` | Terraform modular multi-ambiente + backend remoto | HU-01 |
| 2 | `feature/observability` | Prometheus/Grafana/Jaeger/Loki + alertas + métrica de negocio | HU-02 |
| 3 | `feature/ci-cd-advanced` | SonarQube/JaCoCo, Trivy, semver, notificaciones, aprobación prod | HU-03 |
| 4 | `feature/design-patterns` | Resilience4j (Circuit Breaker+Retry) + Feature Toggle | HU-04 |
| 5 | `feature/testing-coverage` | Cobertura JaCoCo agregada + gate + OWASP ZAP DAST | HU-05 |

La funcionalidad de negocio (HU-06) estaba en el código base y se restauró/validó en la iteración
inicial. Cada incremento se verificó (tests verdes en contenedor; despliegue local en kind).

## 6. Definition of Done (DoD)

Una historia está *Done* cuando: el código compila y los tests pasan en CI; el incremento se
documenta (doc dedicado en `docs/`); pasa los quality gates aplicables (Sonar/Trivy/cobertura); y se
mergea a `develop` mediante PR.
