FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY target/my-app-1.0.1.jar app.jar
EXPOSE 15000
ENTRYPOINT ["java", "-jar", "app.jar"]
