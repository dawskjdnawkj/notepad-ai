# 云记事本后端镜像。
#
# 这是个「只装 jar」的镜像，不在这里编译 —— 编译产物由本地 mvn package 产出后上传。
# 服务器只有 1.6G 内存，跑不动 Maven 构建，编译放在本地做。
# （同机的 mall-resume 项目也是这个做法，已验证可行。）
#
# 构建前先上传：scp backend/target/notepad-backend-0.0.1-SNAPSHOT.jar <服务器>:/opt/notepad/backend/app.jar

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# JVM 参数是为「1.6G 机器上和另一套栈共存」专门调过的：
#   -Xmx160m                  堆上限。这项目数据量很小（向量库才 0.8MB），160m 够用；
#                             再往下压 GC 会变频繁，反而更慢
#   -Xss256k                  线程栈，默认 1M。配合 Tomcat 20 个线程，省下约 90MB 虚拟内存
#   -XX:MaxMetaspaceSize=96m  元空间封顶，不封会缓慢上涨
#   -XX:ReservedCodeCacheSize=48m  默认上限 240MB，用不到那么多
#   -XX:TieredStopAtLevel=1   只用 C1 编译，省下编译线程和 code cache（作品展示站不追峰值吞吐）
#   -XX:MaxDirectMemorySize=32m    限制堆外内存
#   -XX:+UseSerialGC          小堆用 SerialGC 比 G1 更省内存，且这机器 CPU 也弱
#   -XX:+ExitOnOutOfMemoryError    OOM 时干脆退出让容器重启，比僵死好
#   -Duser.timezone           定时任务（提醒、回收站清理）按本地时区跑
ENV JAVA_OPTS="-Xms64m -Xmx160m -Xss256k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai"

# 用非 root 运行；上传目录在 compose 里挂了具名卷
RUN addgroup -S notepad && adduser -S notepad -G notepad \
    && mkdir -p /app/uploads && chown -R notepad:notepad /app

COPY --chown=notepad:notepad app.jar /app/app.jar

USER notepad

EXPOSE 8080

# exec 形式，让 java 成为 PID 1，信号能直接送达（容器的 stop 才不会等超时）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
