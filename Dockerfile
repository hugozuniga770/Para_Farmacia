# Fase 1: Construir el JAR ejecutable con Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
# Usamos 'package' para crear el JAR gordo
RUN mvn clean package

# Fase 2: Crear la imagen final y ligera para correr la aplicación
FROM openjdk:17-jdk-slim
WORKDIR /app

# Copiar solo el JAR final que creamos en la fase anterior
COPY --from=build /app/target/*.jar app.jar

# El comando para arrancar la aplicación
CMD ["java", "-jar", "app.jar"]