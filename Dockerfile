FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/pluggis-*.jar app.jar
# Skrivbar datakatalog även med slumpat UID (OpenShift kör som grupp 0).
RUN mkdir -p /app/data && chgrp -R 0 /app && chmod -R g=u /app
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
