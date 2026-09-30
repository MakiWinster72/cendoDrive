FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
# The committed example is the container baseline; Compose environment overrides it.
RUN cp src/main/resources/application.example.yml src/main/resources/application.yml
# Keep successfully downloaded dependencies across builds, even when the network drops.
RUN --mount=type=cache,target=/root/.m2/repository \
    for attempt in 1 2 3; do \
      mvn -B -DskipTests -Dmaven.wagon.http.retryHandler.count=3 package && exit 0; \
      echo "Maven download failed (attempt $attempt/3); retrying..."; \
      sleep 5; \
    done; \
    exit 1

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
