FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests package
ARG SERVICE
RUN cp "$SERVICE/target/$SERVICE-1.0.0.jar" /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
