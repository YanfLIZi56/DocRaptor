FROM eclipse-temurin:21-jre
WORKDIR /app
# 创建config目录，用于后续挂载外部配置
RUN mkdir -p /app/config
# 复制打包好的jar到容器内
COPY app/DocRaptor.jar app.jar
# 设置时区
ENTRYPOINT ["java","-Duser.timezone=Asia/Shanghai","-jar","app.jar"]
