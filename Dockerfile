FROM node:22.22.3-bookworm-slim@sha256:e21fc383b50d5347dc7a9f1cae45b8f4e2f0d39f7ade28e4eef7d2934522b752 AS web
WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci --ignore-scripts
COPY web/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS java
WORKDIR /build
COPY pom.xml ./
COPY src/ src/
COPY --from=web /build/web/dist/ src/main/resources/META-INF/resources/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
WORKDIR /app
COPY --from=java --chown=10001:10001 /build/target/quarkus-app/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java","-jar","quarkus-run.jar"]
