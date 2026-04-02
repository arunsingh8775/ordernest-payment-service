# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src

RUN ./gradlew clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar
COPY --from=builder /app/src/main/resources/ca.pem /app/ssl/ca.pem
COPY --from=builder /app/src/main/resources/client.keystore.p12 /app/ssl/client.keystore.p12

ENV SPRING_PROFILES_ACTIVE=production
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
