# ---- build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# dependency:resolve follows the project's managed graph; go-offline ignores exclusions/management and trips on a
# transitive jackson SNAPSHOT version range from the passkey library.
RUN chmod +x mvnw && ./mvnw -q -B dependency:resolve dependency:resolve-plugins
COPY src src
RUN ./mvnw -q -B -DskipTests package

# ---- run
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/* \
    && groupadd --system howlers && useradd --system --gid howlers --home /app howlers \
    && mkdir -p /app/data/uploads && chown -R howlers:howlers /app
WORKDIR /app
COPY --from=build /src/target/howlers-backend.jar app.jar
USER howlers
ENV STORAGE_LOCAL_DIR=/app/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/readyz || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
