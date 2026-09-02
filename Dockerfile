FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x gradlew
RUN ./gradlew clean bootJar --no-daemon

EXPOSE 8080

CMD ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar build/libs/*.jar"]