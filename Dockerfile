# Fase 1: Construir la aplicación con Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean install

# Fase 2: Crear la imagen final para correr la aplicación
FROM openjdk:17-jdk-slim
WORKDIR /app

# Copiar el webapp-runner y el archivo .war de la fase de construcción
COPY --from=build /app/target/dependency/webapp-runner.jar .
COPY --from=build /app/target/*.war app.war

# Exponer el puerto y correr la aplicación
EXPOSE 10000
CMD ["java", "-jar", "webapp-runner.jar", "--port", "10000", "app.war"]