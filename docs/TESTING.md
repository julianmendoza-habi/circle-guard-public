# Estrategia de pruebas — CircleGuard

Pirámide de pruebas del proyecto, cómo correr cada nivel, y cómo se integran en CI. Incluye los dos
gaps cerrados en la iteración "Pruebas completas": **reportes de cobertura (JaCoCo agregado + gate)**
y **DAST con OWASP ZAP**.

---

## Pirámide

```
         ╱ DAST (OWASP ZAP) ╲          ← seguridad dinámica, contra el gateway desplegado
        ╱  E2E (e2e-tests)   ╲         ← flujos extremo-a-extremo contra un entorno real
       ╱ Performance (Locust) ╲        ← carga sobre el gateway
      ╱  Integración (TC)      ╲       ← repos JDBC con Testcontainers (-Pintegration)
     ╱   Unit (JUnit5+Mockito)  ╲      ← lógica de cada servicio (rápida, sin infra)
    ╱_____ Cobertura (JaCoCo) ____╲    ← transversal: mide y verifica los niveles de arriba
```

| Nivel | Dónde | Infra | Comando |
|---|---|---|---|
| Unit | `services/*/src/test/java` | ninguna (H2 en runtime) | `./gradlew test` |
| Integración | `services/*/src/integrationTest` | Docker (Testcontainers) | `gradle test -Pintegration` (en CI/Linux) |
| E2E | `e2e-tests/` | entorno desplegado | `scripts/ci/run-e2e-with-kube-port-forward.sh <ns>` |
| Performance | `tests/performance/locustfile.py` | entorno desplegado | `scripts/ci/run-locust-with-kube-port-forward.sh` |
| Cobertura | raíz (`jacocoAggregatedReport`) | ninguna | `./gradlew test jacocoAggregatedReport` |
| DAST | `scripts/ci/zap-baseline.sh` | gateway corriendo + Docker | `scripts/ci/run-zap-with-kube-port-forward.sh <ns>` |

---

## 1. Unit

JUnit 5 + Mockito (vía `spring-boot-starter-test`). No requieren infra: las pruebas JPA usan H2
(`testRuntimeOnly("com.h2database:h2")`) y las dependencias externas se mockean. El `build.gradle.kts`
raíz **excluye** del task `test` por defecto las clases de integración/Testcontainers (por path y
nombre) para que `test` nunca dispare el descubrimiento de Docker en agentes sin él.

Ejemplos de esta iteración y la anterior:
[`PromotionClientFallbackTest`](../services/circleguard-dashboard-service/src/test/java/com/circleguard/dashboard/client/PromotionClientFallbackTest.java),
[`NotificationDispatcherFeatureToggleTest`](../services/circleguard-notification-service/src/test/java/com/circleguard/notification/service/NotificationDispatcherFeatureToggleTest.java).

```bash
./gradlew test
# o un módulo / una clase:
./gradlew :services:circleguard-auth-service:test --tests "*FallbackTest"
```

> **Fix de esta iteración:** `NotificationRetryTest` y `ExposureNotificationListenerTest`
> (`@SpringBootTest` que mockean `JavaMailSender`) fallaban al cargar el contexto —
> `MailHealthContributorAutoConfiguration` lanzaba *"Beans must not be empty"*. Se desactivó el
> health indicator de mail **solo en perfil test**
> ([`application-test.yml`](../services/circleguard-notification-service/src/test/resources/application-test.yml));
> prod conserva el indicador real.

## 2. Integración (Testcontainers)

Repos JDBC contra PostgreSQL real vía Testcontainers, bajo `src/integrationTest` (o
`src/test/.../integration`). Solo corren con `-Pintegration` (necesitan Docker; en Jenkins se monta
el socket). Ej.: `IdentityVaultJdbcIntegrationTest`.

```bash
gradle test -Pintegration        # requiere Docker disponible (DOCKER_HOST)
```

## 3. E2E

El módulo [`e2e-tests`](../e2e-tests) ejerce flujos completos (login → anonimización → validación de
acceso) contra un entorno **desplegado**, leyendo `E2E_*` URLs. El helper abre port-forwards a los
servicios del namespace y corre el módulo:

```bash
scripts/ci/run-e2e-with-kube-port-forward.sh circleguard-dev
```

## 4. Performance (Locust)

[`tests/performance/locustfile.py`](../tests/performance/locustfile.py) genera carga sobre el
gateway. El helper hace port-forward y lanza Locust:

```bash
scripts/ci/run-locust-with-kube-port-forward.sh
```

## 5. Cobertura (JaCoCo) — NUEVO

Cada módulo emite JaCoCo XML (lo consume SonarQube). La iteración añadió, en el `build.gradle.kts`
raíz, dos tareas que dan **una** vista combinada y un **gate**:

- **`jacocoAggregatedReport`** — fusiona la *execution data* de todos los microservicios en un
  reporte XML + HTML único en `build/reports/jacoco/aggregate/`. Excluye ruido no testeable de forma
  unitaria (`**/*Application.class`, `config`, `dto`, `model`, `entity`) para que el % refleje lógica real.
- **`jacocoCoverageVerification`** — falla el build si la cobertura de **líneas** agregada cae por
  debajo de `-PcoverageMin` (default `0.30`, ajustable).

```bash
./gradlew test jacocoAggregatedReport
# gate (sube el umbral a medida que crece el suite):
./gradlew jacocoCoverageVerification -PcoverageMin=0.30
```

**Línea base medida (esta iteración):** cobertura de líneas agregada ≈ **35%**. Por módulo:

| Módulo | LINE % |
|---|---|
| gateway | 84% |
| notification | 60% |
| identity | 50% |
| form | 43% |
| auth | 42% |
| promotion | 26% |
| file | 21% |
| dashboard | 16% |

El gate por defecto (30%) deja margen sobre la base; promotion/file/dashboard son los próximos
candidatos a subir cobertura.

## 6. DAST — OWASP ZAP baseline — NUEVO

Escaneo **pasivo** (spider + reglas pasivas; no ataca el objetivo, es seguro contra un entorno
compartido) del gateway con la imagen oficial `ghcr.io/zaproxy/zaproxy`.

- [`scripts/ci/zap-baseline.sh`](../scripts/ci/zap-baseline.sh) — corre `zap-baseline.py` contra una
  URL, escribe reportes HTML/JSON/MD en `build/reports/zap/`, y degrada con elegancia (si no hay
  Docker, se salta con exit 0 — igual que `trivy-scan.sh`). Por defecto **warn-only**
  (`ZAP_FAIL_ON_FINDINGS=0`); con `=1` falla ante alertas etiquetadas FAIL.
- [`.zap/rules.tsv`](../.zap/rules.tsv) — tuning de reglas (IGNORE el ruido de dev; WARN en los
  headers de seguridad pendientes de endurecer; promover a FAIL cuando se implementen).
- [`scripts/ci/run-zap-with-kube-port-forward.sh`](../scripts/ci/run-zap-with-kube-port-forward.sh) —
  port-forward del gateway desplegado y lanza el scan.

```bash
# contra un gateway ya accesible:
scripts/ci/zap-baseline.sh http://127.0.0.1:18087
# o contra el namespace desplegado (port-forward incluido):
scripts/ci/run-zap-with-kube-port-forward.sh circleguard-dev
```

> El runtime de ZAP usa `docker run --network host` para alcanzar el port-forward del gateway;
> el escaneo se ejecuta como una etapa del pipeline contra el ambiente desplegado.

---

## Integración en CI ([`ci/Jenkinsfile.dev.groovy`](../ci/Jenkinsfile.dev.groovy))

- **Stage "Gradle Unit Tests + Coverage":** corre `test jacocoAggregatedReport`, publica el JUnit y
  **archiva el reporte agregado**. Gate opcional vía params `ENFORCE_COVERAGE` + `COVERAGE_MIN`.
- **Stage "OWASP ZAP DAST (dev)":** tras el deploy, guardado por el param `RUN_ZAP` (default off);
  archiva `build/reports/zap/**`. `ZAP_FAIL_ON_FINDINGS` controla si rompe el build.
- Ambos siguen el patrón de gates opcionales ya usado por `RUN_SONARQUBE` / `TRIVY_FAIL_ON_FINDINGS`:
  degradan con elegancia y no rompen el pipeline por defecto.
