# ---------- 构建阶段：Maven + JDK25 编译 ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -q -DskipTests clean package

# ---------- 运行阶段：仅 JRE ----------
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/mdm-platform.jar /app/mdm-platform.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/mdm-platform.jar"]
