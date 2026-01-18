# Stage 1: Java 25 jre
FROM eclipse-temurin:25.0.1_8-jre-noble AS jre

# Stage 2: CUDA runtime for GPU support (choose a recent CUDA runtime tag)
FROM nvidia/cuda:12.5.0-runtime-ubuntu22.04

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre /opt/java/openjdk /opt/java/openjdk

WORKDIR /app

# Logging directory
RUN mkdir -p /var/log/solesonic-llm-api
VOLUME /var/log/solesonic-llm-api

COPY target/*.jar app.jar

EXPOSE 8080

# Run without SSL profile - TLS is terminated at nginx
ENTRYPOINT ["java", "-Dspring.profiles.active=prod-nginx", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]