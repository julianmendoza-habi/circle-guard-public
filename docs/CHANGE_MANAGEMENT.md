# Change Management y Release Notes — CircleGuard

Proceso formal de gestión de cambios, versionado/etiquetado de releases, generación de release notes
y planes de rollback. (Rúbrica 6, 5%.)

---

## 1. Proceso de Change Management

Todo cambio sigue un flujo controlado, trazable en GitHub y los pipelines:

1. **Solicitud / historia** — se registra como historia en el backlog (ver
   [`AGILE_METHODOLOGY.md`](AGILE_METHODOLOGY.md)) y una rama `feature/*`.
2. **Implementación** — commits **Conventional Commits** (`feat`, `fix`, `chore`, `docs`…).
3. **Revisión** — Pull Request a `develop`; CI ejecuta tests + cobertura + SonarQube + Trivy.
4. **Integración** — merge a `develop` → despliegue automático a **DEV** (`Jenkinsfile.dev`).
5. **Promoción a STAGE** — `develop → stage` → QA/E2E/Locust (`Jenkinsfile.stage`).
6. **Promoción a PROD** — `stage → master` con **aprobación manual** (`input` en
   `Jenkinsfile.master`) → despliegue a `circleguard-master`.
7. **Release** — se etiqueta la versión (§2) y se generan las release notes (§3).

Clasificación de cambios: **Standard** (features/fixes por el flujo normal), **Normal** (cambios de
infra vía Terraform con `plan` revisado), **Emergency** (hotfix desde `master`, documentado a
posteriori).

## 2. Versionado y etiquetado de releases (SemVer)

Se usa **versionado semántico** `MAJOR.MINOR.PATCH`:
- **MAJOR**: cambios incompatibles de API/contrato.
- **MINOR**: nueva funcionalidad retrocompatible (`feat:`).
- **PATCH**: correcciones retrocompatibles (`fix:`).

La versión se deriva de los Conventional Commits con
[`scripts/ci/semantic-version.sh`](../scripts/ci/semantic-version.sh) (lo usa el pipeline). El
**sistema de etiquetado** son tags Git anotados `vMAJOR.MINOR.PATCH` sobre `master`:

```bash
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

Las imágenes de contenedor se etiquetan en paralelo (`dev-`, `stage-`, `prod-latest` + tag por
commit), de modo que cada release tiene artefactos rastreables.

## 3. Generación de Release Notes

Automatizada con [`scripts/generate-release-notes.ps1`](../scripts/generate-release-notes.ps1), que
recoge los commits desde el último tag y los formatea:

```powershell
./scripts/generate-release-notes.ps1 -Version "1.0.0" -OutputPath "docs/RELEASE_NOTES.md"
```

Las notas de cada versión se mantienen en [`RELEASE_NOTES.md`](RELEASE_NOTES.md).

## 4. Planes de Rollback

Rollback por capa, del más rápido al más profundo:

### 4.1 Aplicación (Kubernetes)
Cada `kubectl apply` crea una nueva ReplicaSet; revertir es inmediato:
```bash
kubectl rollout undo deployment/circleguard-<svc> -n circleguard-<env>
kubectl rollout status deployment/circleguard-<svc> -n circleguard-<env>
# a una revisión concreta:
kubectl rollout history deployment/circleguard-<svc> -n circleguard-<env>
kubectl rollout undo deployment/circleguard-<svc> --to-revision=<N> -n circleguard-<env>
```

### 4.2 Imagen / versión
Re-desplegar la imagen estable anterior (tag por commit o `prod-latest` previo):
```bash
kubectl set image deployment/circleguard-<svc> <svc>=circleguard/<svc>:<tag-estable> -n circleguard-<env>
```
Para releases: re-checkout del tag anterior (`git checkout v0.9.0`) y re-ejecutar el pipeline.

### 4.3 Infraestructura (Terraform)
El estado remoto (S3 versionado + lock DynamoDB) permite revertir:
```bash
terraform plan    # revisar el diff antes de aplicar cualquier reversión
# revertir el código IaC al commit/tag previo y:
terraform apply   # reconcilia al estado anterior
```
Los datos (RDS/ElastiCache) se protegen con snapshots/backups antes de cambios destructivos.

### 4.4 Base de datos
Migraciones Flyway versionadas; para revertir, aplicar una migración compensatoria (`V<n>__...`)
o restaurar el snapshot previo de RDS. **Nunca** editar una migración ya aplicada.

## 5. Criterios para activar rollback
Error rate 5xx sostenido por encima del umbral (alerta `HighHttp5xxRate`), `ServiceDown`, fallo del
smoke/E2E post-deploy, o latencia p95 fuera de SLO (`HighHttpLatencyP95`). Las alertas
(Alertmanager) disparan la decisión; ver [`OBSERVABILITY.md`](OBSERVABILITY.md).
