# 宝塔部署手册 (zzdzz-novel-generate-mvp)

> **要「照着做」的简版流程 → `docs/deploy-bt-quick.md`；agent 可调用的 skill → `.mimocode/skills/bt-deploy-novelgen/`。本文是详版：路径速查、配置原文、坑的完整背景、排障全集。**
>
> 单机部署：1 台宝塔 Linux（与 zzdzz-blog 同机），**PG（pgvector 容器）+ Java21 后端 + Vue 前端（Nginx）**，无域名。
>
> 参考来源：zzdzz-blog 的 `doc/deploy-bt.md` / `doc/ops-bt.md`，按本项目（Spring Boot 多模块 + Vite）改造。
> 首次上线：2026-10-16（本文即当时的实操记录，可按「日常升级」重复执行）。

## 一、架构与入口

| 项 | 值 |
|---|---|
| 服务器 | `101.126.22.219`（CentOS 7，宝塔面板，同机还跑着 zzdzz-blog；域名 `zzdzz.cn` 已解析到此机） |
| 前端入口 | **`https://zzdzz.cn:8888/`**（必须带 `https://`——nginx 不支持同端口 http/https 混听，`http://…:8888` 会得 400，见 §七） |
| 随机衍生入口 | **`https://zzdzz.cn:8888/derive/`**（API-only，无独立前端；derive-app 8091 挂 context-path=/derive，同站反代，2026-10-16 深夜上线） |
| 后端 | Spring Boot jar，本机 `127.0.0.1:8090`（**只绑回环**，经 nginx `/api/` 反代对外） |
| 衍生后端 | `novelgen-derive` systemd，jar=`/www/wwwroot/novelgen-derive/novelgen-derive-app.jar`，库 `derive_gen`，8091 只绑回环，经 `/derive/` 反代；依赖 Redis 容器 `novel-ai-redis`（127.0.0.1:6379，redis:7-alpine，登录会话 fail-closed） |
| 进程管理 | systemd：`novelgen.service`（开机自启；博客当年用的宝塔 supervisor 插件现已停摆，见 §八） |
| 数据库 | 复用 docker 容器 `zzdzz-pg-zzdzz-pg-1`（镜像 **pgvector/pgvector:pg16**，host 端口 `35432`），库名 `novel_gen` |
| JDK | Temurin 21.0.12.1，`/usr/local/jdk-21`（清华 Adoptium 镜像装的，无 yum 依赖） |
| 管理员 | `admin`——**口令 = 本地开发库的 admin 口令**（2026-10-16 数据迁移把 users 表整体覆盖过来了；不一致时按 §六 SQL 重设） |

端口取舍：80/443 已被博客站点占用（`server_name 101.126.22.219 zzdzz.cn`，证书 SAN 覆盖 zzdzz.cn/www.zzdzz.cn），且云安全组未放行新端口；**8888 在本机 firewalld 已放行且实测外网可达**，故 novelgen 站点用 8888（HTTPS）。若将来宝塔组件要占 8888，先迁站再让位。

## 二、SSH 密钥

| 项 | 路径 |
|---|---|
| 本地私钥 | 仓库 `keys/101.126.22.219_id_ed25519`（**已 gitignore，严禁入库**） |
| 用法 | `ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219` |
| Windows 权限 | OpenSSH 要求 ACL 只留当前用户；复制过来后要 `icacls <key> /inheritance:r /remove "Authenticated Users" "BUILTIN\Users" "BUILTIN\Administrators"` 再 `icacls <key> /grant "%USERNAME%:(F)"`，否则报 `bad permissions` |

密钥从 zzdzz-blog 复制而来（同机同 root）。私钥文件的 ACL 收紧前，Windows 上的 ssh 会直接拒用该密钥。

## 三、服务器路径速查

### 后端（Spring Boot）

| 路径 | 内容 |
|---|---|
| `/www/wwwroot/novelgen/novelgen-app.jar` | 当前运行的可执行 fat jar |
| `/www/wwwroot/novelgen/config/application-local.yaml` | **生产密钥配置**（DB 密码 / JWT / LLM key / master-key；权限 600，www 所有，不入 git） |
| `/www/wwwroot/novelgen/logs/app.log` | 应用日志（systemd 重定向） |
| `/www/wwwroot/novelgen/data/{style,novel}/` | `novelgen.style-dir` / `novelgen.novel-dir` 落点（导入器/冒烟 Runner 用，正常服务不读） |
| `/etc/systemd/system/novelgen.service` | systemd 单元（User=www，WorkingDirectory=/www/wwwroot/novelgen，`-Xmx2g`，时区 Asia/Shanghai） |

### 前端（Vue dist）

| 路径 | 谁在 serve |
|---|---|
| `/www/wwwroot/novelgen-ui/` | **nginx 8888 站点** root（`index.html` + `assets/`） |

### nginx

| 路径 | 用途 |
|---|---|
| `/www/server/panel/vhost/nginx/novelgen.conf` | novelgen 站点配置（listen 8888；宝塔面板「网站」里也能看到） |
| `/www/wwwlogs/novelgen.log` / `novelgen.error.log` | access / error log |

### PostgreSQL（docker，与博客共用容器）

| 项 | 值 |
|---|---|
| 容器 | `zzdzz-pg-zzdzz-pg-1`（`pgvector/pgvector:pg16`，restart=always） |
| novelgen 库 | `novel_gen`（2026-10-16 建；Flyway 自动迁移到 V44） |
| 博客库 | `zzdzz_blog`（同容器，互不相干） |
| 连接 | host：`127.0.0.1:35432`，用户 `postgres`（密码在两侧配置文件里，容器 env `POSTGRES_PASSWORD` 同源） |

```bash
# 连库
docker exec -it zzdzz-pg-zzdzz-pg-1 psql -U postgres -d novel_gen
# 或 host 直连
PGPASSWORD='<pg_pass>' psql -h 127.0.0.1 -p 35432 -U postgres -d novel_gen
```

## 四、编译产物（本机）

```bash
# 后端 fat jar（JAVA_HOME 指向 JDK21；多模块根目录一条命令）
export JAVA_HOME="D:\Program\Java\jdk-21"    # Windows: $env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q clean -DskipTests package
# 产物：novelgen-app/target/novelgen-app-0.1.0-SNAPSHOT.jar

# 前端
cd web && npm run build
# 产物：web/dist/
```

## 五、上传与重启（日常升级）

```bash
KEY=keys/101.126.22.219_id_ed25519
HOST=root@101.126.22.219

# 1) 后端：先停再传（避免 scp 覆盖运行中的 jar 文本文件busy）
ssh -i $KEY $HOST "systemctl stop novelgen"
scp -i $KEY novelgen-app/target/novelgen-app-0.1.0-SNAPSHOT.jar $HOST:/www/wwwroot/novelgen/novelgen-app.jar
ssh -i $KEY $HOST "chown www:www /www/wwwroot/novelgen/novelgen-app.jar; systemctl start novelgen"

# 2) 前端：直接覆盖 dist，无需重启任何服务
#    必须 scp -r web/dist/. 整体覆盖（Vite content-based hash，挑文件必漏 chunk → SPA 白屏）
scp -i $KEY -r web/dist/. $HOST:/www/wwwroot/novelgen-ui/
# 3) 浏览器强刷 Ctrl+Shift+R
```

前端只推**一处**（`/www/wwwroot/novelgen-ui/`）——不像博客要 nginx/Go 两处同步，后端不 serve 静态资源。

升级后验证：

```bash
ssh -i $KEY $HOST "systemctl is-active novelgen; tail -20 /www/wwwroot/novelgen/logs/app.log"
# 本机外网冒烟（必须 https://）
curl -s -o /dev/null -w '%{http_code}\n' https://zzdzz.cn:8888/
```

## 六、数据库与账号初始化（首次部署已做，重装才需要）

```bash
# 1) 建库（pgvector 扩展由 Flyway V16 自动 CREATE EXTENSION）
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -c 'CREATE DATABASE novel_gen;'

# 2) pgcrypto：V3 种子注释里的改口令 SQL 要用 digest()，本地老库当年手工装过，新库必须补
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d novel_gen -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto;'

# 3) 首次启动 jar 会自动跑 Flyway（V1~V44），然后设置 admin 口令：
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d novel_gen \
  -c "UPDATE users SET password_hash = encode(digest(('admin:' || '<自设口令>')::bytea, 'sha256'), 'hex') WHERE username = 'admin';"
```

### 数据迁移（2026-10-16 已执行，把本地全量数据搬上线）

线上原是空库；已把本地开发库整库搬上去（结果：27 本书 / 828 章 / 526 向量 / 405 素材卡，与本地逐表一致；Flyway 44 个校验全过）。可复用的步骤：

```bash
# 1) 本地：容器内 dump（二进制必须容器内落文件，别走 PowerShell 管道重定向）
docker exec pg-vector pg_dump -U postgres -d novel_gen -Fc -f /tmp/novel_gen.dump
docker cp pg-vector:/tmp/novel_gen.dump var/novel_gen.dump

# 2) 上传（约 81MB）
scp -i keys/101.126.22.219_id_ed25519 var/novel_gen.dump root@101.126.22.219:/tmp/

# 3) 服务器：停服 → 删建库 → pg_restore → 起服（脚本见 var/migrate.sh 同款逻辑）
systemctl stop novelgen
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -c 'DROP DATABASE IF EXISTS novel_gen;'
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -c 'CREATE DATABASE novel_gen;'
docker cp /tmp/novel_gen.dump zzdzz-pg-zzdzz-pg-1:/tmp/
docker exec zzdzz-pg-zzdzz-pg-1 pg_restore -U postgres -d novel_gen --no-owner --no-privileges /tmp/novel_gen.dump
systemctl start novelgen
```

迁移连带效果（要知道的三件事）：

1. **admin 口令 = 本地库口令**（users 表被 dump 覆盖，部署当天现设的口令作废）。
2. `llm_providers` 密文随库过来，master-key 两侧同套（§九），解密正常；`prompt_templates` 的库内提示词编辑也一并上线。
3. `flyway_schema_history` 随库走——本地与 jar 必须同源（同一工作树打的包），否则启动时 checksum 校验会炸。

### 随机衍生系统（derive-app）部署（2026-10-16 深夜上线）

与 novelgen-app 完全隔离的第二条线（D11/F 架构）：独立 jar / 独立库 `derive_gen`（V100~V117 迁移）/ 独立端口 8091 / 依赖 Redis。**没有独立前端**——纯 API（`/api/derive/*`），本地靠脚本/agent 直连；线上挂在同一 8888 站点的 `/derive/` 路径下（应用 `server.servlet.context-path: /derive`，nginx `location ^~ /derive/` 原样透传，不新开端口、不碰云安全组）。

| 项 | 值 |
|---|---|
| 进程 | systemd `novelgen-derive`（`/www/wwwroot/novelgen-derive/`，-Xmx1g，User=www） |
| 配置 | `/www/wwwroot/novelgen-derive/config/application-local.yaml`（600；DB=`derive_gen`、Redis、JWT 与 novelgen 同套生产密钥、LLM key/master-key） |
| 数据库 | `derive_gen`（同 PG 容器；上线时全量 `pg_restore`：33 本书/13 run 与本地一致） |
| Redis | **宝塔面板实例 `redis_PcNS`**（容器 `redis_pcns-redis_PcNS-1`，host `127.0.0.1:26739`，带密码；密码在面板与 derive 的 `config/application-local.yaml`，勿入 git。登录会话 fail-closed，Redis 挂则登录全挂） |
| 验证 | `POST /derive/api/auth/login`（带 Origin，应返 JSON 而非 403 CORS）；`GET /derive/api/derive/ping` 未登录应 401 A0003 |

部署/重建步骤：本机 `mvn -pl novelgen-derive-app -am package -DskipTests` → `pg_dump -Fc derive_gen` → scp（jar/配置/unit/dump）→ 停服 → drop/create `derive_gen` → `pg_restore` → 起服 → `nginx -t && reload`。两个实弹踩过的坑：

1. **日志文件 root 占位陷阱**：root 用 `: > logs/app.log` 新建的文件是 root 所有，`www` 用户 bash 重定向打不开 → **java 根本没执行就 exit 1**、app.log 永远 0 字节、systemd 10 秒崩循环且日志全空。修法：truncate 后必须 `chown www:www` 该文件（或先 `touch` 再 chown）。排查口径：**journalctl 显示秒退但应用日志为空 = 先查日志文件属主**。
2. **Redis 以面板实例为准，别自建完就不管了**：部署当时服务器上确无其他 Redis（首查 `docker ps` 只有 PG 容器），遂自建了 `novel-ai-redis`（6379）；随后用户在宝塔面板引入实例 **`redis_PcNS`**（**26739**，dk 面板 compose 管理，带密码）——已把 derive 配置切到该实例（host/port/密码）并删除自建容器。教训：**动中间件前先看面板 + 全端口确认**；自建的中间件与面板实例并存时，以用户指定的面板实例为准并清理自建物。

另：derive-app 的 base `application.yaml` 已加 `server.forward-headers-strategy: framework`（与 novelgen 同款 CORS 修复，见 §十二）。`web/` 前端**没有任何 `/api/derive` 页面**——8888 主站 UI 不会显示衍生库的 33 本书，属预期，不是数据没迁。

## 七、nginx 站点要点（8888）

配置文件：`/www/server/panel/vhost/nginx/novelgen.conf`。要点与博客站点同款教训：

- **`/api/` 整段 `gzip off` + `proxy_buffering off` + `proxy_read_timeout 600s`**：SSE 端点是 `GET /api/pipeline/stream`（生成进度流），gzip buffer 会攒包、buffering 会憋流；生成任务单请求也可能是分钟级，600s 与后端 `llm.read-timeout: 600s` 对齐。
- **`client_max_body_size 200m`**：导入小说 docx/mobi 可以几十 MB。
- SPA：`try_files $uri $uri/ /index.html`。
- 改完必走：

```bash
nginx -t && nginx -s reload    # -t 语法不过绝不 reload
```

### HTTPS（2026-10-16 加，域名正门）

- 站点为 **`listen 8888 ssl` 单块**，复用博客同机的证书 `/www/server/panel/vhost/cert/101.126.22.219/{fullchain,privkey}.pem`（CN=zzdzz.cn，SAN 含 www.zzdzz.cn，**2026-12-10 到期**，续期走宝塔面板证书管理，两侧共用）。
- **踩过的坑：nginx 不支持同端口 http/https 混听**——先试过「一个 server 做301、一个 server 挂 ssl」，同 `listen 8888` 会被合并成一个地址：ssl 标志占主导（plain http 打进来直接 400），且 https 会落到排第一的 301 块形成**死循环**（curl `-L --max-redirs 5` 打满）。结论：8888 只挂 ssl，访问必须带 `https://`；`http://zzdzz.cn:8888` / `http://IP:8888` 得 400 属预期，不是故障。
- 博客占着 443 的 `zzdzz.cn`（root 路径），novelgen 想走干净的 `https://zzdzz.cn/` 得换子域名（加 DNS + 新证书）。

## 八、进程管理（systemd）

博客当年走宝塔「supervisor 插件」（`/www/server/panel/plugin/supervisor/profile/blog_server.ini`）。**该插件的 supervisord 当前并未运行**（面板里 blog_server 状态 ERROR，实际 blog-server 是 PPID=1 的孤儿进程），所以 novelgen 直接用 systemd，更稳：

```bash
systemctl status  novelgen
systemctl stop    novelgen
systemctl start   novelgen
systemctl restart novelgen
tail -f /www/wwwroot/novelgen/logs/app.log     # 应用日志
journalctl -u novelgen -n 50 --no-pager       # systemd 层（启停失败先看这里）
```

单元文件要点：`User=www`、`WorkingDirectory=/www/wwwroot/novelgen`、`--spring.profiles.active=local`（加载 `./config/application-local.yaml`）、`Restart=on-failure`、`-Xmx2g`（机器总内存 7.7G，博客+PG+面板共用，别再往上加）。

## 九、配置与密钥约定

Spring 外部配置查找顺序（jar 同级 `./config/` 会被加载）：

```
./config/application-local.yaml   ← 线上实际用的（profile=local）
jar 内 application.yaml            ← 默认值 + 环境变量占位（NOVELGEN_DB_URL 等）
```

- `application-local.yaml`（含 DB 密码 / JWT secret / LLM api-key / master-key）：**本地版与线上版都在 gitignore**，靠本文件+SSH 通道维护，永不入库。
- 线上 JWT secret、admin 口令均为部署当次现生成（`openssl rand`），与本地开发值不同。
- `novelgen.llm.master-key`：库内 `llm_providers` 密文的 AES 主钥；**换机器/换库要与密文同套迁移**，否则「模型接入」页全部解密失败。
- 8090 在 nginx 同机反代，`server.address: 127.0.0.1` 已写进线上配置——**不要再从 firewalld/安全组放行 8090**。

## 十、安全收尾清单

1. admin 口令：种子哈希是公开的；2026-10-16 数据迁移后线上口令=本地库口令，如需独立于本地，按 §六 SQL 重设。
2. `chmod 600 /www/wwwroot/novelgen/config/application-local.yaml`（已做）。
3. `keys/` 私钥目录已在 `.gitignore`；**不要**把 `application-local.yaml`、`var/` 下的临时脚本 commit 进 git。
4. 博客侧教训照抄：8090 不对外；nginx 站点禁访问 `application*.yaml` 等敏感文件（conf 里已有正则）。

## 十一、排障速查

```bash
# 服务活着但页面 502
systemctl is-active novelgen
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8090/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"<口令>"}'
tail -100 /www/wwwroot/novelgen/logs/app.log

# nginx 层（502/404/SSE 卡流）
nginx -t
tail -50 /www/wwwlogs/novelgen.error.log
ss -tlnp | grep -E ':(8090|8888|35432)\s'

# 数据库层
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d novel_gen -c '\dt' | head
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d novel_gen -c 'SELECT version, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;'

# LLM 出口连通（服务器→MiniMax；401=通、超时=网络墙）
curl -s -o /dev/null -w '%{http_code}\n' --connect-timeout 10 https://api.minimaxi.com/v1/models

# 防火墙（8888 应在 public 放行列表里）
firewall-cmd --zone=public --list-ports
```

日志里出现 Flyway 校验失败 / checksum 不匹配：**不要**手改历史迁移，按 AGENTS 铁律用 `flyway_schema_history` 重对 checksum 或重放。

## 十二、本次首部署记录与已知坑（2026-10-16）

| 事项 | 结论 |
|---|---|
| 部署范围 | 只上了 **novelgen-app**（8090/novel_gen）。`novelgen-derive-app`（8091/derive_gen/Redis，架构 D11/F 的衍生壳）**未部署**——需要时另起 systemd 单元 + 迁移目录 `migration-derive`，端口 8091 记得也走 nginx。 |
| 修了一个启动 bug | derive 代码进了共享模块 novelgen-service 后，主应用 `@MapperScan` 没扫 `derive.mapper`，**新打的 jar 一律起不来**（`DeriveBookService` 缺 `NovelMapper`）。已修：`NovelGenApplication` 扫描包补 `com.zzdzz.novelgen.derive.mapper`，并用 `FullyQualifiedAnnotationBeanNameGenerator` 防同名接口撞 bean（与 `DeriveGenApplication` 同款；全仓 0 处 `@Resource`/`@Qualifier`，按类型注入零风险）。**本地以后重打 jar 也要带上这个修复**。 |
| JDK 安装 | CentOS 7 glibc 2.17，Temurin 21 可直接跑；Adoptium 官方 API 走 GitHub 超时，改清华镜像 `mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/` 秒下。 |
| 迁移与扩展 | Flyway 44 个迁移全绿；**pgcrypto 不在迁移里**，新库要手工 `CREATE EXTENSION`（§六）。 |
| PG 容器 | 复用博客的 pgvector 容器即可，不必另起；但两本书共用一个 postgres 用户密码，改密要两侧同步。 |
| 端口 | 云安全组没放行的新端口一律不通；8080（blog 直连）实测外网已封，8888 通——新增服务优先从 firewalld 已放行端口里挑。 |
| supervisor 插件 | 面板插件只剩 supervisor 且 supervisord 未运行，blog_server 在面板里显示 ERROR 是历史遗留；别用它，systemd 为准。 |
| HTTPS 同端口混听 | 8888 曾试「301 块 + ssl 块」双 server：被合并成一个地址 → https 落 301 块**死循环**、http 得 400。终态=单块 `listen 8888 ssl`，访问必须 `https://`（详见 §七）。 |
| 数据迁移 | 2026-10-16 全量 `pg_dump -Fc` → `pg_restore` 上线（27 书/828 章/526 向量/405 卡，行数与本地一致，Flyway 44 校验全过）；**users 表随之覆盖，线上 admin 口令改回本地口令**。dump 本地留档 `var/novel_gen.dump`（81MB，gitignore 内）。 |
| 浏览器登录 403「Invalid CORS request」 | 同源被误判跨域：浏览器 `Origin: https://…`，后端在 nginx 后看到 `scheme=http` → Spring 同源判定失败 → 落进只放行 localhost:5173 的 allowlist。**修复**：jar 配 `server.forward-headers-strategy: framework`（base application.yaml）+ nginx `/api/` 段补 `X-Forwarded-Port $server_port` / `X-Forwarded-Host $http_host`。**验证必须带 `-H "Origin: https://zzdzz.cn:8888"`**——curl 不带 Origin 会绕过整条判定，测出假 200（这次就骗过了首轮验收）。 |
