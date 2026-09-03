# AI-OpenPlatform 后端镜像（多阶段构建）
# 用法：docker compose -f docker-compose.deploy.yml up -d --build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# canal 等私有依赖随源码带入（file:// 仓库），先拷 pom 与 libs 以便缓存依赖层
COPY pom.xml ./
COPY libs ./libs
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/ai-open-platform-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
