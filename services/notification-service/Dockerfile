# syntax=docker/dockerfile:1
# Same file in backend/, services/audit-service/ and services/notification-service/.
# Tests are not run here: CI runs them (they need Docker for Testcontainers).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Spring Boot's layered extraction: dependencies change rarely, so their layer stays cached between releases.
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
