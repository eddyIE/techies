# One Dockerfile for every Spring Boot module.
#
# The build stage takes no ARG on purpose: it is byte-identical for all services, so Docker
# builds and caches it exactly once and the other five images reuse that layer instead of
# each running its own full Maven build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache on the POMs alone, so a source change does not re-download.
COPY pom.xml .
COPY common/pom.xml common/
COPY discovery-server/pom.xml discovery-server/
COPY api-gateway/pom.xml api-gateway/
COPY identity-service/pom.xml identity-service/
COPY catalog-service/pom.xml catalog-service/
COPY inventory-service/pom.xml inventory-service/
COPY order-service/pom.xml order-service/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY . .
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
ARG MODULE
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
