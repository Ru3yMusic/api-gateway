# api-gateway

Punto de entrada único para toda la arquitectura de **RUBY MUSIC**. Basado en **Spring Cloud Gateway** (stack reactivo), se encarga de validar JWT, enrutar hacia los microservicios de negocio vía load balancing, y propagar la identidad del usuario autenticado.

---

## Responsabilidades

1. **Validación JWT (RS256):** Intercepta el `Authorization: Bearer <token>` en cada ruta protegida, lo verifica con la clave pública RSA y rechaza con `401` si es inválido o expirado.
2. **Propagación de identidad:** Extrae los claims del token y los reenvía como headers internos a los servicios downstream.
3. **Routing con load balancing:** Resuelve las URIs `lb://` contra Eureka y balancea entre instancias disponibles.
4. **CORS:** Configuración permisiva en desarrollo (todos los orígenes, métodos y headers).
5. **Timeouts:** Connect 5s / Response 10s por defecto.

---

## Stack

| Componente | Versión |
|---|---|
| Java | 21 |
| Spring Boot | 3.2.5 |
| Spring Cloud | 2023.0.1 |
| Spring Cloud Gateway (reactive) | — |
| Eureka Client | — |
| Spring Cloud Config Client | — |
| JJWT | 0.12.5 |
| Lombok | — |

> **Importante:** Este servicio usa el stack **reactivo** (WebFlux). No se debe añadir `spring-boot-starter-web` al `pom.xml` — son incompatibles.

---

## Puerto

| Servicio | Puerto |
|---|---|
| api-gateway | **8080** |

---

## Orden de arranque

```
discovery-service (8761) → config-server (8888) → api-gateway (8080) → business services
```

---

## Rutas configuradas

La configuración detallada de rutas vive en `config-server` (`config/api-gateway.yml`):

| Ruta | Servicio destino | JWT requerido |
|---|---|---|
| `POST /api/v1/auth/**` | `auth-service` | No |
| `GET/POST /api/v1/catalog/**` | `catalog-service` | Sí |
| `GET/POST /api/v1/interactions/**` | `interaction-service` | Sí |
| `GET/POST /api/v1/playlists/**` | `playlist-service` | Sí |
| `GET/POST /api/v1/social/**` | `social-service` | Sí |

---

## Filtro JWT — `JwtAuthFilter`

Implementado como `AbstractGatewayFilterFactory`. Se aplica a todas las rutas protegidas.

### Flujo

```
Request entrante
    │
    ▼
¿Header Authorization: Bearer <token>?
    ├── No  → 401 Unauthorized
    └── Sí  → Verificar firma RS256 con clave pública RSA
                  ├── Inválido/expirado → 401 Unauthorized
                  └── Válido → Extraer claims y mutar request
                                    │
                                    ▼
                            Headers internos propagados:
                            X-User-Id           (JWT sub)
                            X-User-Email        (claim: email)
                            X-Display-Name      (claim: displayName)
                            X-Profile-Photo-Url (claim: profilePhotoUrl)
                                    │
                                    ▼
                            Continuar hacia el microservicio
```

### Headers internos propagados

| Header | Origen en JWT |
|---|---|
| `X-User-Id` | `sub` (UUID del usuario) |
| `X-User-Email` | claim `email` |
| `X-Display-Name` | claim `displayName` |
| `X-Profile-Photo-Url` | claim `profilePhotoUrl` |

Los microservicios downstream **confían en estos headers** y nunca re-validan el token.

---

## Estructura del proyecto

```
api-gateway/
├── src/
│   ├── main/
│   │   ├── java/com/rubymusic/gateway/
│   │   │   ├── ApiGatewayApplication.java       ← entry point
│   │   │   └── filter/
│   │   │       └── JwtAuthFilter.java            ← RS256 JWT validation + header propagation
│   │   └── resources/
│   │       └── application.yml                   ← solo nombre + import config-server
│   └── test/
│       └── java/com/rubymusic/gateway/
│           └── ApiGatewayApplicationTests.java
└── pom.xml
```

> La configuración completa (puertos, rutas, CORS, timeouts) se sirve desde `config-server` en `config/api-gateway.yml`.

---

## Variables de entorno

| Variable | Descripción | Usado en |
|---|---|---|
| `JWT_PUBLIC_KEY` | Clave RSA pública en Base64 (X.509 DER, sin headers PEM) | `JwtAuthFilter` |

La variable es inyectada desde `config/api-gateway.yml` en el `config-server`.

---

## Build & Run

```bash
# Build
mvn clean package -DskipTests

# Run
mvn spring-boot:run

# Test
mvn test -Dtest=ApiGatewayApplicationTests
```

---

## Endpoints Actuator

```
GET http://localhost:8080/actuator/health
GET http://localhost:8080/actuator/info
```
