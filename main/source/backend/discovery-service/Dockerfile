# Build with context directory: backend/
FROM ghcr.io/graalvm/jdk-community:21 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat ./
COPY gradle ./gradle
COPY discovery-service ./discovery-service
COPY gateway-service ./gateway-service
COPY auth-service ./auth-service
COPY stream-service ./stream-service
COPY chat-service ./chat-service
COPY notification-service ./notification-service
RUN chmod +x gradlew \
    && ./gradlew :discovery-service:bootJar -x test --no-daemon

FROM ghcr.io/graalvm/jdk-community:21
WORKDIR /app
COPY --from=build /app/discovery-service/build/libs/discovery-service-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
