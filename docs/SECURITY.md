# Seguridad — CircleGuard

Postura de seguridad del proyecto: escaneo continuo de vulnerabilidades, gestión de secretos, RBAC y
TLS. (Rúbrica 8, 5%.) Se marca claramente lo **implementado** vs. lo **planificado**.

---

## 1. Escaneo continuo de vulnerabilidades — ✅ implementado
- **Trivy** (imágenes de contenedor) en cada pipeline: [`scripts/ci/trivy-scan.sh`](../scripts/ci/trivy-scan.sh),
  severidades HIGH/CRITICAL, integrado en los 3 Jenkinsfiles (warn-only en dev, configurable a
  fallo). Degrada con elegancia si no hay Trivy/Docker.
- **SonarQube** (análisis estático + security hotspots): plugin en [`build.gradle.kts`](../build.gradle.kts),
  Quality Gate en CI.
- **OWASP ZAP** (DAST, pasivo): [`scripts/ci/zap-baseline.sh`](../scripts/ci/zap-baseline.sh) + stage
  en `Jenkinsfile.dev` contra el gateway desplegado; tuning en [`.zap/rules.tsv`](../.zap/rules.tsv).
  Ver [`TESTING.md`](TESTING.md).

## 2. RBAC — ✅ implementado (Kubernetes)
Cuentas de servicio con permisos mínimos (least privilege) para los componentes que necesitan acceso
a la API de Kubernetes:
- **Prometheus** — `ServiceAccount` + `ClusterRole` (solo lectura de pods/endpoints/nodes para el
  service discovery) + `ClusterRoleBinding`, en [`deploy/k8s/infra/observability.yaml`](../deploy/k8s/infra/observability.yaml).
- **Promtail** — `ServiceAccount` + `ClusterRole` (lectura de pods para descubrir logs) +
  `ClusterRoleBinding`, en [`deploy/k8s/infra/logging.yaml`](../deploy/k8s/infra/logging.yaml).
- Los microservicios corren con la `ServiceAccount` por defecto del namespace (sin permisos sobre la
  API), y los contenedores se ejecutan como **usuario no-root** (`Dockerfile.service`: uid 1000).

## 3. Gestión de secretos
- **Implementado:** los secretos de runtime (jwt/qr/datasource) se inyectan vía **Kubernetes
  Secrets** (`circleguard-shared-secret`), no hardcodeados en las imágenes; el código los lee por
  variable de entorno (relaxed binding de Spring).
- **Planificado (IaC ya lo prepara):** el módulo `rds-postgres` de Terraform crea el secret en **AWS
  Secrets Manager**; la ruta de producción es montar esos secretos (External Secrets / CSI) en lugar
  de Secrets en claro. Ver [`terraform/README.md`](../terraform/README.md).

## 4. TLS para servicios expuestos
- **Implementado:** tráfico interno este-oeste dentro del cluster; el gateway es el único punto de
  entrada y valida tokens QR firmados (HMAC) con expiración corta.
- **Planificado:** terminación **TLS** en el Ingress/LoadBalancer del gateway (cert-manager +
  Let's Encrypt en EKS) y TLS en ElastiCache/MSK (variables ya previstas en los módulos Terraform).

## 5. Seguridad de la aplicación (defensa en profundidad)
- **Anonimización (privacy-by-design):** la identidad real solo existe cifrada en reposo dentro del
  vault de identity (`IdentityEncryptionConverter` + hash con salt); el resto del sistema solo ve
  `anonymousId`. Ver [`DESIGN_PATTERNS.md`](DESIGN_PATTERNS.md).
- **AuthN/AuthZ:** JWT en la cadena auth→servicios; Spring Security con filtros JWT; `/actuator/**`
  expuesto solo para probes/métricas (no datos de negocio).
- **K-Anonymity** en analítica (supresión de grupos < umbral) para evitar reidentificación.

## 6. Roadmap de seguridad (no requerido)
NetworkPolicies por namespace, mTLS entre servicios (service mesh — bono), rotación automática de
secretos, y firma/SBOM de imágenes (cosign/syft).
