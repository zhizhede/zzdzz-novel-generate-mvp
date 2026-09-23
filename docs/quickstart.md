# 本地启动步骤

三步：起数据库 → 起后端（8090）→ 起前端（5173）。全程在项目根目录的 Git Bash 中执行。

## 0. 前置条件（只需确认一次）

- Docker Desktop 已启动，PostgreSQL 容器 `pg-vector` 在跑（数据库名 `novel_gen`）。
- JDK 21 在 `D:\Program\Java\jdk-21`（shell 默认 JAVA_HOME 是 17，**起后端必须显式 export 成 21**）。
- `src/main/resources/application-local.yaml` 存在（gitignore，含 API key 与数据库连接）。
- `web/node_modules` 已安装（没有则先 `cd web && npm install`）。

## 1. 数据库

```bash
docker start pg-vector        # 若容器没在跑；已在跑则跳过
# 验证：
docker exec pg-vector psql -U postgres -d novel_gen -c "select 1"
```

## 2. 后端（8090）

```bash
# 若 8090 被旧进程占用，先杀：netstat -ano | grep :8090 找 PID，taskkill //F //PID <PID>
export JAVA_HOME="D:\Program\Java\jdk-21"
(mvn -q -DskipTests spring-boot:run -Dspring-boot.run.profiles=local > var/web.log 2>&1 &)
```

日志看 `var/web.log`。等大约 10-30 秒，出现 `novelgen-server` 初始化完成即就绪，验证：

```bash
curl -s -X POST http://localhost:8090/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'
# 返回 {"code":"00000",...} 即成功
```

## 3. 前端（5173）

```bash
cd web && (npm run dev > ../var/vite.log 2>&1 &)
```

日志看 `var/vite.log`，出现 `VITE ready` 即就绪。浏览器打开 <http://localhost:5173>，用 `admin / admin123` 登录。

## 常见坑

- **起后端报 Java 版本错**：忘了 export JAVA_HOME 成 jdk-21。
- **8090/5173 起不来**：多半是端口被上次没退干净的进程占着，先 netstat 找 PID 杀掉。
- **重启后端后接口 401**：JWT 仍有效，但 curl 的 cookie 文件可能过期，重新 login 一次。
- **有生成任务在跑时不要 `mvn clean`**：会删运行中后端的 target/classes，只许 `mvn compile`。
