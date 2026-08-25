FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
# 五套 Gold 数据集由 pom.xml 映射到 classpath:benchmark，构建镜像时必须一并复制。
COPY scripts/AUTO_Benchwork/datasets/five_scenario ./scripts/AUTO_Benchwork/datasets/five_scenario
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS="-Xms256m -Xmx768m"

COPY --from=build /app/target/*.jar /app/zhiguang-be.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/zhiguang-be.jar"]
