FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/flash-kv.jar flash-kv.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "flash-kv.jar"]
