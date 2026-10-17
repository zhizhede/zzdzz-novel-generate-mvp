# 宝塔部署速览（简要版）

> 这是「照着做」的主干流程；完整路径表、坑位记录与排障命令在 **`docs/deploy-bt.md`（详版）**，改配置前先读详版对应章节。
> 当前状态（2026-10-16）：已上线 `https://zzdzz.cn:8888`，本地数据已全量迁移（27 书/828 章）。

## 0. 架构一页看

```
浏览器 ── https://zzdzz.cn:8888 ── nginx(vhost novelgen.conf, 仅SSL)
                                        ├─ /        → /www/wwwroot/novelgen-ui/   (Vue dist)
                                        └─ /api/    → 127.0.0.1:8090              (Spring Boot jar)
                                                              └─ systemd: novelgen
数据库  127.0.0.1:35432/novel_gen  ← docker zzdzz-pg-zzdzz-pg-1（pgvector:pg16，与博客 zzdzz_blog 共用容器）
JDK     /usr/local/jdk-21（Temurin 21，清华镜像）
SSH     仓库 keys/101.126.22.219_id_ed25519（gitignore；Windows 需 icacls 收权）
```

同机还跑着 zzdzz-blog（80/443 + 8080）——**别动它的 vhost，别放行 8090**。

## 1. 本机构建

```bash
# 后端（多模块根目录）
$env:JAVA_HOME="D:\Program\Java\jdk-21"; mvn -q clean -DskipTests package
# → novelgen-app/target/novelgen-app-0.1.0-SNAPSHOT.jar

# 前端
cd web && npm run build          # → web/dist/
```

## 2. 上传与重启（日常升级的全部内容）

```powershell
$K = "keys\101.126.22.219_id_ed25519"; $H = "root@101.126.22.219"

# 后端：先停 → 传 → 起（覆盖运行中 jar 会因文件占用失败）
ssh -i $K $H "systemctl stop novelgen"
scp -i $K novelgen-app\target\novelgen-app-0.1.0-SNAPSHOT.jar ${H}:/www/wwwroot/novelgen/novelgen-app.jar
ssh -i $K $H "chown www:www /www/wwwroot/novelgen/novelgen-app.jar; systemctl start novelgen"

# 前端：整目录覆盖，无需重启任何服务（Vite hash 挑文件必漏 chunk）
scp -i $K -r web\dist\. ${H}:/www/wwwroot/novelgen-ui/
```

## 3. 验证（升级后必跑）

```bash
ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219 \
  "systemctl is-active novelgen; tail -20 /www/wwwroot/novelgen/logs/app.log"

# 外网（必须 https://，http 会得 400 属预期）
curl -s -o /dev/null -w '%{http_code}\n' https://zzdzz.cn:8888/
# 登录验证：JSON 写进文件再发（PowerShell 内联会吃引号）
# POST https://zzdzz.cn:8888/api/auth/login  {"username":"admin","password":"<本地库口令>"}
```

起不来先看：`journalctl -u novelgen -n 50`（systemd 层）→ `logs/app.log`（应用层，Flyway/bean 错都在这）。

## 4. 数据迁移（重建库 / 本地数据上新环境时）

```bash
# 本地（容器内落文件，别走 PowerShell 管道）
docker exec pg-vector pg_dump -U postgres -d novel_gen -Fc -f /tmp/novel_gen.dump
docker cp pg-vector:/tmp/novel_gen.dump var/novel_gen.dump
scp -i <key> var/novel_gen.dump root@101.126.22.219:/tmp/

# 服务器：停服 → 删建 → 恢复 → 起服
systemctl stop novelgen
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -c 'DROP DATABASE IF EXISTS novel_gen;'
docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -c 'CREATE DATABASE novel_gen;'
docker cp /tmp/novel_gen.dump zzdzz-pg-zzdzz-pg-1:/tmp/
docker exec zzdzz-pg-zzdzz-pg-1 pg_restore -U postgres -d novel_gen --no-owner --no-privileges /tmp/novel_gen.dump
systemctl start novelgen
```

三个连带效果：① admin 口令=源库口令（users 表被覆盖）；② llm_providers 密文可解（两侧 master-key 同套）；③ flyway_schema_history 随库走，**jar 必须与源库同一工作树打的包**，否则 checksum 校验炸。

全新空库另需（详见 deploy-bt.md §六）：`CREATE EXTENSION pgcrypto`（改口令 SQL 用）+ Flyway 首启自动建表。

## 5. 首次/重建部署

按 `docs/deploy-bt.md` 执行，顺序：JDK（清华镜像）→ 目录 → 建库+pgcrypto → `config/application-local.yaml`（600 权限）→ systemd 单元 → nginx vhost（8888 ssl 单块）→ §6 验证。配置模板参照服务器现网（可从线上 `cat` 回抄）。

## 5. 随机衍生系统（derive-app，2026-10-16 上线）

第二条线，与主站隔离：API-only（无前端），入口 **`https://zzdzz.cn:8888/derive/`**；库 `derive_gen`（同 PG 容器）；进程 systemd `novelgen-derive`（`/www/wwwroot/novelgen-derive/`，8091 绑回环）；依赖**宝塔面板 Redis 实例 `redis_PcNS`**（127.0.0.1:26739，密码在面板/derive 配置，勿入 git）。升级/迁移套路与主站同构：

```powershell
# 后端：构建 → 停 → 传 → 起
mvn -q -pl novelgen-derive-app -am package -DskipTests
ssh -i $K $H "systemctl stop novelgen-derive"
scp -i $K novelgen-derive-app\target\novelgen-derive-app-0.1.0-SNAPSHOT.jar ${H}:/www/wwwroot/novelgen-derive/novelgen-derive-app.jar
ssh -i $K $H "chown www:www /www/wwwroot/novelgen-derive/novelgen-derive-app.jar; systemctl start novelgen-derive"
# 数据：pg_dump/pg_restore 换库名 derive_gen（脚本见 var/migrate.sh 同款逻辑 + deploy-bt.md §六derive 小节）
```

两个专属坑：① root 建的 `logs/app.log` 必须 chown 给 www，否则 java 没起 bash 就 exit 1、日志全空崩循环；② Redis 用**面板现成实例** `redis_PcNS`（26739），别只查 6379 就自建容器（曾因此返工）。主站 UI 不显示衍生库数据是预期（web/ 没有 /api/derive 页面）。

## 6. 坑速查（一行一条，详版有完整背景）

| 症状/场景 | 结论 |
|---|---|
| `http://…:8888` 得 400 | 预期——8888 是 HTTPS-only，nginx 不支持同端口混听 |
| ssh 报 `bad permissions` | Windows 私钥 ACL 过宽，`icacls` 收权到当前用户 |
| PS 里 `&&` 报错 / curl 变 Invoke-WebRequest | PS 5.1：串联用 `cmd /c`，HTTP 用 `curl.exe` |
| 内联 JSON 报「请求不合法」 | PS 吃引号——写文件后 `--data-binary @file` |
| jar 起来即崩 `DeriveBookService 缺 NovelMapper` | 修复已在 `NovelGenApplication`（derive.mapper 扫描+FQCN），确认打的是新码 |
| Flyway checksum 不匹配 | jar 与库不同源，别手改历史迁移（AGENTS 铁律） |
| 新库改口令报 `digest() does not exist` | 缺 pgcrypto，`CREATE EXTENSION IF NOT EXISTS pgcrypto` |
| 前端白屏 404 chunk | dist 没整体覆盖，必须 `scp -r web/dist/.` |
| SSE 卡流/整段蹦 | 检查 novelgen.conf `/api/` 段 `gzip off` + `proxy_buffering off` |
| 想加子域名/改 443 | 博客占着 zzdzz.cn 根路径，需另加 DNS+新证书，先读详版 §七 |
