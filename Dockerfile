FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache dos2unix curl
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN dos2unix mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
