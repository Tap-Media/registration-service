FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
RUN mkdir -p /app/target && mvn -f pom.xml dependency:go-offline

COPY . .
RUN mvn -f pom.xml clean package -DskipTests
RUN ls -al /app/target/
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/target/registration-service-*-dirty-SNAPSHOT.jar /app/registration-service.jar
RUN ls -al /app
EXPOSE 50051
# Run the Java application
CMD ["java", "-jar", "-Dmicronaut.environments=dev", "registration-service.jar"]

