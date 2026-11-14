# syntax=docker/dockerfile:1.7
# ---- Compilación ----
FROM eclipse-temurin:21-jdk AS compilacion
WORKDIR /fuente
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -q package -DskipTests \
    && cp target/*.jar /fuente/app.jar

# ---- Ejecución ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 servired
USER servired
WORKDIR /app
COPY --from=compilacion /fuente/app.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
