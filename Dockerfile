# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copiar solo el pom.xml primero para cachear las dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copiar el código fuente y compilar
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Crear usuario no-root para seguridad
RUN addgroup -S rubymusic && adduser -S rubymusic -G rubymusic

# Heap-dump destination — must exist and be writable by the runtime user BEFORE
# the JVM starts, otherwise -XX:+HeapDumpOnOutOfMemoryError silently fails and
# we lose the diagnostic evidence. Mount this path as a volume in production
# so dumps survive container restarts.
RUN mkdir -p /var/log/api-gateway && chown rubymusic:rubymusic /var/log/api-gateway

# Copiar el JAR desde la etapa de build
COPY --from=build /app/target/*.jar app.jar

RUN chown rubymusic:rubymusic app.jar

USER rubymusic

# Puerto del api-gateway
EXPOSE 8080

# Health check
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# OOM diagnostic flags:
#   HeapDumpOnOutOfMemoryError  — dump heap to disk so we can post-mortem a leak
#   HeapDumpPath                — fixed location; mount /var/log/api-gateway as a volume
#   ExitOnOutOfMemoryError      — terminate immediately so the orchestrator restarts
#                                 a clean instance instead of leaving a half-broken JVM
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/var/log/api-gateway/heap-dump.hprof", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
