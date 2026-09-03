FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode clean package

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="DDB API v3 Gateway" \
      org.opencontainers.image.authors="Michael Büchner <m.buechner@dnb.de>" \
      org.opencontainers.image.source="https://github.com/mbuechner/ddb-api-v3"
RUN useradd --system --uid 10001 spring
USER 10001
WORKDIR /app
COPY --from=build /workspace/target/ddb-api-v3-gateway-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
