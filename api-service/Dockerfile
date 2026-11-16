FROM maven:3-eclipse-temurin-27 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode clean package

FROM eclipse-temurin:25-jre
# Size the heap from the container limit; leave room for native buffers, stacks and metaspace.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"
LABEL org.opencontainers.image.title="DDB API v3 Service" \
      org.opencontainers.image.authors="Michael Büchner <m.buechner@dnb.de>" \
      org.opencontainers.image.source="https://github.com/mbuechner/ddb-api-v3"
RUN useradd --system --uid 10001 spring
USER 10001
WORKDIR /app
COPY --from=build /workspace/target/ddb-api-v3-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
