# --- build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# --- run stage ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Render (and any host without a bind-mounted volume) needs the sample
# dataset baked into the image. Local docker-compose still bind-mounts the
# top-level ../data folder over /data (see docker-compose.yml), which
# shadows this at runtime - so this COPY only matters for hosts like Render.
COPY data/ /data/
# Render injects its own PORT at runtime and expects the app to bind to it;
# application.yml's server.port reads ${PORT:8080}, so this default only
# matters when running the image standalone (docker-compose sets its own
# env, unaffected either way).
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
