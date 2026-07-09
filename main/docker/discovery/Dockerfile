# Minimal runtime image for the Eureka discovery service.
# Build context: main/source/backend/ (run `./gradlew :discovery-service:bootJar` first)
FROM ghcr.io/graalvm/jdk-community:21

RUN microdnf install -y curl && microdnf clean all && rm -rf /var/cache/dnf

WORKDIR /app
COPY discovery-service/build/libs/discovery-service-*.jar /app/app.jar

EXPOSE 8761

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -sf http://localhost:8761/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
