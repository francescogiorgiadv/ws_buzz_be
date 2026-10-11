# syntax=docker/dockerfile:1

# --- build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# La cache di ~/.m2 sopravvive tra una build e l'altra: le dipendenze si scaricano una volta sola.
# I test si eseguono prima del push (vedi DEPLOY_DOCKER_BUILD_SERVER.md, fase A3).
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

# --- runtime ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
