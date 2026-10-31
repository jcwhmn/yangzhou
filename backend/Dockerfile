# YPJ-118(spec 0087):宿主 gradle :api:bootJar 先行,产物经 script/app-infra.ps1 复制为 app.jar
# profile/数据源全靠运行期 env(compose 注入),镜像单一
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
