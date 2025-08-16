FROM openjdk:21-jdk

WORKDIR /app

RUN mkdir -p /var/log/solesonic-llm-api
VOLUME /var/log/solesonic-llm-api

COPY ../target/*.jar app.jar

EXPOSE 8443

ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]