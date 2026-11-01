FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre

WORKDIR /app
RUN apt-get update \
  && apt-get install --no-install-recommends -y wget tesseract-ocr tesseract-ocr-deu tesseract-ocr-eng \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --system --create-home --uid 10001 appuser
COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health/readiness | grep -q 'UP'

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
