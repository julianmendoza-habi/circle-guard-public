# Patrones de diseño — CircleGuard

Catálogo de los patrones aplicados en CircleGuard: los que **ya existían** en el código (aquí
documentados) y los **añadidos en la iteración "Patrones de diseño"** (Resilience4j Circuit Breaker
+ Retry, y Feature Toggle). Cada patrón apunta a su punto de entrada en el código.

> Contexto: arquitectura de microservicios (8 servicios Spring Boot) con un *vault* de
> anonimización, una cadena de tokens JWT/QR, mensajería Kafka y un fan-out de notificaciones
> multicanal. La resiliencia y la observabilidad de estos saltos entre servicios son el foco de los
> patrones nuevos.

---

## Resumen

| Patrón | Categoría | Dónde | Estado |
|---|---|---|---|
| **Circuit Breaker + Retry** | Resiliencia (cloud) | `IdentityClient` (auth→identity), `PromotionClient` (dashboard→promotion) | ✅ **Nuevo** |
| **Feature Toggle** | Configuración | `NotificationDispatcher` + `NotificationFeatureProperties` | ✅ **Nuevo** |
| Vault de anonimización (Tokenization / Pseudonymization) | Seguridad / privacidad | `IdentityVaultService` | Documentado |
| Cadena de tokens JWT + QR | Seguridad | auth (emisión) → gateway (`QrValidationService`) | Documentado |
| Cache-aside (Redis) | Rendimiento | `QrValidationService` (`user:status:<id>`) | Documentado |
| Event-Driven / Publish-Subscribe | Mensajería | Listeners Kafka (`ExposureNotificationListener`, `PriorityAlertListener`, `CircleFencedListener`) | Documentado |
| Dispatcher multicanal / Fan-out (Scatter) | Concurrencia | `NotificationDispatcher` | Documentado |
| Strategy | GoF (comportamiento) | `EmailService` / `SmsService` / `PushService` (interfaz + impl) | Documentado |
| Retry + Recover (Spring Retry) | Resiliencia | `SmsServiceImpl`, `PushServiceImpl` (`@Retryable`/`@Recover`) | Documentado |
| K-Anonymity (supresión) | Privacidad | `KAnonymityFilter`, `AnalyticsService` | Documentado |
| Builder | GoF (creacional) | `RestTemplateBuilder` / `WebClient.Builder`, `IdentityMapping.builder()` | Documentado |
| Repository | Acceso a datos | Spring Data JPA (`IdentityMappingRepository`, …) | Documentado |

---

## 1. Patrones nuevos (esta iteración)

### 1.1 Circuit Breaker + Retry (Resilience4j)

**Problema.** Dos saltos **síncronos** entre servicios pueden propagar fallos en cascada: si el
servicio destino se cae o responde lento, el llamante acumula peticiones bloqueadas y termina
cayendo también.

- `auth → identity`: [`IdentityClient`](../services/circleguard-auth-service/src/main/java/com/circleguard/auth/client/IdentityClient.java)
  resuelve el `anonymousId` durante el login.
- `dashboard → promotion`: [`PromotionClient`](../services/circleguard-dashboard-service/src/main/java/com/circleguard/dashboard/client/PromotionClient.java)
  trae estadísticas de salud para los paneles.

**Solución.** Decorar ambas llamadas con `@Retry` (reintentos con *backoff* exponencial para fallos
transitorios) y `@CircuitBreaker` (abre el circuito tras una tasa de fallo sostenida y deja de
golpear al servicio caído), más un **fallback** que define la degradación.

```
        ┌──────── @Retry (aspecto externo) ────────┐
        │   ┌──── @CircuitBreaker ────┐             │
  call ─┼──▶│  CLOSED → cuenta fallos │── éxito ───▶┼─▶ resultado
        │   │  OPEN   → corta llamada │             │
        │   └─────────────────────────┘             │
        │   reintentos agotados / circuito OPEN     │
        └───────────────▶ fallbackMethod ───────────┘
```

El orden por defecto de los aspectos de Resilience4j es `Retry( CircuitBreaker( … ) )`, así que el
`fallbackMethod` se coloca en `@Retry` (el aspecto más externo): solo dispara **después** de agotar
los reintentos o cuando el circuito está `OPEN` (la `CallNotPermittedException` no está en la lista
blanca de reintento, así que cae directo al fallback).

**Estrategia de degradación (distinta por criticidad):**

| Cliente | Criticidad | Fallback |
|---|---|---|
| `IdentityClient` | **Crítico** (sin `anonymousId` no se emite token) | *Fail-fast*: lanza [`IdentityServiceUnavailableException`](../services/circleguard-auth-service/src/main/java/com/circleguard/auth/client/IdentityServiceUnavailableException.java) → `LoginController` responde HTTP 500 (no inventa un id) |
| `PromotionClient` | No crítico (analítica) | *Graceful*: devuelve un payload centinela `{"error":"Service unavailable", …}` para que los endpoints de analítica sigan respondiendo |

**Configuración** (`application.yml` de cada servicio; instancias `identityService` /
`promotionService`):

```yaml
resilience4j:
  circuitbreaker:
    instances:
      identityService:        # (promotionService en dashboard)
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50           # OPEN si >=50% del ventaneo falla
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true      # estado vía /actuator/health
  retry:
    instances:
      identityService:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2     # 200ms -> 400ms
        retry-exceptions:                     # solo fallos transitorios de transporte/servidor
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

**Integración con la observabilidad** (ver [`OBSERVABILITY.md`](OBSERVABILITY.md)). El starter
`resilience4j-spring-boot3` publica automáticamente en el registro Micrometer/Prometheus ya
existente:

- `resilience4j_circuitbreaker_state{name="identityService",state="open|closed|half_open"}`
- `resilience4j_circuitbreaker_calls_total{kind="successful|failed|not_permitted"}`
- `resilience4j_retry_calls_total{kind=...}`

y, con `register-health-indicator: true`, el estado del circuito aparece en `/actuator/health`
(grupo agregado; **no** en el probe de *readiness*, así que un circuito `OPEN` no provoca que
Kubernetes mate el pod).

**Cómo verificar:**
- Runtime: `GET /actuator/circuitbreakers` lista las instancias y su estado; `GET /actuator/health`
  muestra el indicador. Las métricas salen por `/actuator/prometheus`.
- Tests (unitarios, sin proxy AOP — validan el contrato de degradación):
  [`PromotionClientFallbackTest`](../services/circleguard-dashboard-service/src/test/java/com/circleguard/dashboard/client/PromotionClientFallbackTest.java),
  [`IdentityClientFallbackTest`](../services/circleguard-auth-service/src/test/java/com/circleguard/auth/client/IdentityClientFallbackTest.java).

---

### 1.2 Feature Toggle (configuración)

**Problema.** El dispatcher de notificaciones hace fan-out a tres canales (email, SMS, push). Ante
una incidencia de un proveedor (p. ej. Twilio caído) hay que poder **silenciar un canal** sin
desplegar código nuevo.

**Solución.** Un *toggle* por canal expuesto como configuración externa, consumido por el dispatcher.

- [`NotificationFeatureProperties`](../services/circleguard-notification-service/src/main/java/com/circleguard/notification/config/NotificationFeatureProperties.java):
  `@ConfigurationProperties(prefix = "circleguard.features.channels")` con `email/sms/push`
  (default `true`, así el toggle es *opt-out*).
- [`NotificationDispatcher`](../services/circleguard-notification-service/src/main/java/com/circleguard/notification/service/NotificationDispatcher.java):
  un canal deshabilitado se **omite por completo** (no se renderiza su plantilla ni se invoca su
  envío), por lo que no cuesta nada cuando está apagado.

```yaml
circleguard:
  features:
    channels:
      email: true
      sms: true
      push: true
```

Se puede sobreescribir por entorno (ConfigMap) o por variable de entorno con *relaxed binding*,
p. ej. `CIRCLEGUARD_FEATURES_CHANNELS_SMS=false` para mutear SMS durante una incidencia.

**Cómo verificar:**
[`NotificationDispatcherFeatureToggleTest`](../services/circleguard-notification-service/src/test/java/com/circleguard/notification/service/NotificationDispatcherFeatureToggleTest.java)
deshabilita SMS y comprueba que ni se renderiza ni se envía, mientras email y push sí disparan.

---

## 2. Patrones existentes (documentados)

### Vault de anonimización (Tokenization / Pseudonymization)
[`IdentityVaultService`](../services/circleguard-identity-service/src/main/java/com/circleguard/identity/service/IdentityVaultService.java)
mapea de forma determinista `realIdentity ↔ anonymousId` usando un **hash SHA-256 con salt** para la
búsqueda, persiste la identidad real **cifrada en reposo**
([`IdentityEncryptionConverter`](../services/circleguard-identity-service/src/main/java/com/circleguard/identity/util/IdentityEncryptionConverter.java))
y registra cada acceso como evento de auditoría
([`IdentityAccessEvent`](../services/circleguard-identity-service/src/main/java/com/circleguard/identity/event/IdentityAccessEvent.java)).
El resto del sistema solo ve `anonymousId`: la PII queda aislada en un único servicio.

### Cadena de tokens JWT + QR
auth emite un JWT ligado al `anonymousId`; el gateway
([`QrValidationService`](../services/circleguard-gateway-service/src/main/java/com/circleguard/gateway/service/QrValidationService.java))
valida un JWT-QR firmado con `qr.secret` (HMAC-SHA), extrae el `subject` (anonymousId) y decide
GREEN/RED. La identidad nunca viaja en claro entre servicios.

### Cache-aside (Redis)
`QrValidationService` consulta el estado de salud actual en Redis (`user:status:<anonymousId>`,
`StringRedisTemplate`) en la ruta caliente de validación de acceso, evitando ir a la base de datos
en cada escaneo.

### Event-Driven Architecture / Publish-Subscribe
El servicio de notificaciones reacciona a eventos de dominio publicados en Kafka mediante
*listeners* desacoplados (`ExposureNotificationListener`, `PriorityAlertListener`,
`CircleFencedListener`), que disparan la cascada de notificaciones. Productores y consumidores no se
conocen entre sí.

### Dispatcher multicanal / Fan-out (Scatter)
`NotificationDispatcher` hace *scatter* asíncrono a email/SMS/push con `CompletableFuture.allOf`,
agregando el resultado sin bloquear por canal. (Ahora con Feature Toggle por canal — §1.2.)

### Strategy
Los canales son interfaces (`EmailService`, `SmsService`, `PushService`) con implementaciones
intercambiables (Twilio, Gotify, SMTP…), seleccionadas por Spring. El dispatcher depende de la
abstracción, no de la implementación.

### Retry + Recover (Spring Retry — preexistente)
[`SmsServiceImpl`](../services/circleguard-notification-service/src/main/java/com/circleguard/notification/service/SmsServiceImpl.java)
y `PushServiceImpl` ya usaban `@Retryable` + `@Recover` para reintentar envíos a proveedores
externos. Convive con Resilience4j: Spring Retry protege los **envíos salientes** del notification
service; Resilience4j protege los **saltos REST internos** (§1.1).

### K-Anonymity (supresión)
`AnalyticsService` / `KAnonymityFilter` suprimen recuentos por debajo de un umbral (`<5`) antes de
exponer analítica agregada, evitando reidentificación en grupos pequeños.

### Builder / Repository
`RestTemplateBuilder` y `WebClient.Builder` construyen clientes HTTP instrumentados por Micrometer;
`IdentityMapping.builder()` para entidades. El acceso a datos usa el patrón Repository de Spring Data JPA.

---

## 3. Cómo se valida

- **CI (Linux):** SonarQube reporta el catálogo de *code smells*/duplicación; JaCoCo mide la
  cobertura de los tests nuevos (ver [`ci/README.md`](../ci/README.md)).
- **Runtime:** `/actuator/circuitbreakers`, `/actuator/health` y `/actuator/prometheus` exponen el
  estado y las métricas de los circuit breakers.
- **Local:** el build JVM no compila en este entorno (loopback AF_UNIX bloqueado, ver `HANDOFF.md`);
  los tests unitarios de fallback y del toggle se ejecutan en CI.

## 4. Follow-ups opcionales (no requeridos)
- Añadir `@TimeLimiter` + `@Bulkhead` a los clientes REST para acotar latencia y aislar pools.
- Panel Grafana dedicado de estado de circuit breakers (la métrica ya se exporta).
- Servicio/flag store centralizado (p. ej. Unleash) si los feature toggles crecen.
