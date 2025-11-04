# Stage 1: Java 21 JRE
FROM eclipse-temurin:21-jre AS jre

# Stage 2: CUDA runtime for GPU support (choose a recent CUDA runtime tag)
FROM nvidia/cuda:12.5.0-runtime-ubuntu22.04

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Copy Java 21 JRE from stage 1
COPY --from=jre /opt/java/openjdk /opt/java/openjdk

WORKDIR /app

# Logging directory
RUN mkdir -p /var/log/solesonic-llm-api
VOLUME /var/log/solesonic-llm-api

# Copy the pre-built application
COPY target/*.jar app.jar

EXPOSE 8443

# Keep existing runtime flags
ENTRYPOINT ["java", "-Dspring.profiles.active=prod,ssl", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]