FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Claude Code (claude -p) för läget PLUGGIS_CLAUDE_BACKEND=claude-code, så att Pluggis kan gå på ditt
# Claude-abonnemang. Stäng av med: docker compose build --build-arg INSTALL_CLAUDE_CODE=false
ARG INSTALL_CLAUDE_CODE=true
RUN if [ "$INSTALL_CLAUDE_CODE" = "true" ]; then \
      apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
      curl -fsSL https://claude.ai/install.sh | bash && \
      cp -L /root/.local/bin/claude /usr/local/bin/claude && chmod 755 /usr/local/bin/claude && \
      rm -rf /root/.local /root/.claude /var/lib/apt/lists/* && \
      claude --version; \
    fi

COPY --from=build /src/target/pluggis-*.jar app.jar
# Skrivbar datakatalog och hemkatalog (Claude Code skriver i ~/.claude) även med slumpat UID (OpenShift kör som grupp 0).
RUN mkdir -p /app/data /app/home && chgrp -R 0 /app && chmod -R g=u /app
ENV HOME=/app/home DISABLE_AUTOUPDATER=1
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
