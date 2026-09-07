FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 작성자: 김진우 — CI에서 검증한 실행 JAR만 이미지에 포함한다.
COPY --chown=10001:10001 build/libs/*-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
