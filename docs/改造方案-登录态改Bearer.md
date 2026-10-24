# 改造方案：登录态从 HttpOnly Cookie 改为 Authorization: Bearer（随机衍生前端）

> 状态：**方案（未实施，用户定调「先写设计文档，禁止直接改代码」）**。方案层文档（宪法 §三.④），落地时以本方案为准拆任务单；与《最高约束.md》/AGENTS.md 冲突时以上位为准。
> 日期：2026-10-17。发起：用户在宝塔环境 F12 观察到请求头「只有 Cookie、没有 Authorization」，确认此前 Cookie 方案非本人设计，定调改为 Bearer 形式。

## 一、背景与现状

**现状链路（Cookie 形态，谁写的）**

| 环节 | 现状 | 位置 |
|---|---|---|
| 登录签发 | JWT 只种 HttpOnly Cookie `derive_token`，**响应体不回传 token** | `novelgen-derive-app/.../AuthController.login`（37-42 行） |
| 前端请求 | `fetch(..., { credentials: 'same-origin' })`，全前端代码 **零** `Authorization` 字样 | `web/src/deriveApi.js:15` |
| 登录态校验 | DeriveShell 挂载时调 `GET /api/auth/me`（靠 Cookie 过） | `web/src/views/derive/DeriveShell.vue` |
| 后端鉴权 | 拦截器 **Cookie 优先，兼容 `Authorization: Bearer`**（本就双通道） | `novelgen-derive-app/.../AuthInterceptor.extractToken` |

- 方案来源：2026-10-17 搭 `novelgen-derive-app` 启动壳时（提交 `e0c50f3`）随代码带入；STATUS 之后只记录了 Cookie 改名 `derive_token`，**无用户「定调用 Cookie」的记录**。用户 2026-10-17 明确：这不是其设计，且要改成 Authorization 形式。
- 公允说明：HttpOnly Cookie 与 Bearer 均为主流做法（前者抗 XSS 略优、后者自管可控）；本次按**用户定调**改 Bearer，不再讨论取舍。
- 主站 `novelgen-app` 同款 Cookie 认证，但主站 UI 已整体下线（2026-10-17），前端只剩衍生页组——**本改造只以衍生链为行为主体，主站后端仅做编译适配**。

## 二、目标与非目标

**目标**

- 浏览器 F12 里每个衍生请求都带 `Authorization: Bearer <jwt>`，登录态不再依赖 Cookie。
- 登录接口在**响应体回传 token**，前端自行存取。
- curl/脚本两种形态都能调：`-H 'Authorization: Bearer ...'`（新正路）与既有 `-b 'derive_token=...'`（兼容保留）。

**非目标**

- 不动后端鉴权语义：`AuthInterceptor` 双通道（Cookie 优先、Bearer 回退）保持原样，Redis 会话吊销（`SessionStore`）不碰。
- 不动主站前端（已下线）与主站登录页行为（无消费者）。
- 不引入刷新令牌/滑动过期等新机制——仍用现有 30 天 JWT + Redis 会话。
- 不改 CORS/网关配置。

## 三、方案设计

### 3.1 认证时序（改后）

```text
登录：POST /api/auth/login
  ├─ 服务端：签发 JWT → Redis 登记会话 → Cookie 照旧种（兼容通道）
  │          └─ 响应体 data = { username, role, token }   ← 新增回传
  └─ 前端：localStorage.setItem('derive_token', data.token)

后续请求：fetch('/derive/...', { headers: { Authorization: 'Bearer ' + localStorage token } })

登出：POST /api/auth/logout → 前端 removeItem('derive_token')（服务端 Redis 删会话 + 清 Cookie 照旧）

启动校验：DeriveShell.checkAuth → localStorage 无 token 直接判未登录（不发请求）
                                          → 有 token 调 GET /api/auth/me（带头）
```

### 3.2 逐文件改动清单

| # | 文件 | 改动 | 备注 |
|---|---|---|---|
| 1 | `novelgen-model/.../vo/LoginVO.java` | record 加第三组件 `String token`；保留二参兼容构造 `LoginVO(username, role)`（=token null） | 两 app 共享 record，加组件是编译期强制波及点 |
| 2 | `novelgen-derive-app/.../controller/AuthController.java` | `login`：签发后 `new LoginVO(username, role, token)` 回传；`me`：走二参构造（token=null） | Cookie 种植代码**不动** |
| 3 | `novelgen-app/.../controller/AuthController.java` | 编译适配：`login` 把 `jwtService.issue(...)` 提为局部变量后三参回传（与 derive 对称）；`me` 走二参构造 | 主站无前端消费者，回传 token 无害且契约一致 |
| 4 | `web/src/deriveApi.js` | `req()` 组 headers 时：localStorage 有 `derive_token` 则附 `Authorization: Bearer <t>`；导出 `setToken/clearToken/hasToken` 三个助手 | 统一封装，四个衍生视图零改动自动带 |
| 5 | `web/src/views/derive/DeriveShell.vue` | `login` 成功后 `setToken(resp.token)`；`logout` 里 `clearToken()`；`checkAuth` 开头无 token 直接 `authed=false` return；类注释「derive_token Cookie」改口 Bearer | 登录门行为不变 |
| 6 | 文件头注释同步 | `deriveApi.js:4`、`router.js:4`、derive `AuthController` 类注释中「HttpOnly cookie」描述改为「localStorage + Authorization: Bearer（Cookie 通道兼容保留）」 | 防注释与实现再次脱节 |

**明确不改**：`AuthInterceptor`（双通道原样）、`JwtService`（`TOKEN_COOKIE` 常量仍被 Cookie 兼容通道与 logout 清 Cookie 用）、`DeriveBootConfig`（login 排除路径不变）、vite 代理/nginx。

### 3.3 关键决策点

1. **Cookie 通道去留 → 保留**（用户上轮选项口径「Cookie 通道保留兼容」）：后端继续种 HttpOnly Cookie，拦截器不改。收益：既有 curl 脚本（STATUS 记录的宝塔验收脚本全是 `-b 'derive_token=...'` 形态）不断；代价：前端切换后浏览器仍会自动带 Cookie，F12 里 Cookie 头仍可见——**「改成 Bearer」的验收口径是「必须有 Authorization 头」，不是「必须没有 Cookie 头」**。
2. **token 存放位置 → `localStorage`（键名 `derive_token`，与 Cookie 同名但空间隔离）**：最简单、刷新不丢；接受 XSS 可偷 token 的弱点（衍生系统为内网口令级管理台，风险可接受，方案层记录该取舍）。
3. **`/api/auth/me` 不回 token**：响应 `token: null`，仅作校验用；token 只在登录时签发一次。
4. **无 token 的启动校验直接判未登录**（不发 me）：部署后所有在线用户被强制重新登录一次换 token——一次性成本，换取「不再有隐性 Cookie 依赖」。
5. **主站后端同步回传 token**：不为省几行搞两套 VO 契约；主站行为对现有消费方零影响（UI 已删）。

### 3.4 风险与兼容

| 风险 | 应对 |
|---|---|
| dist 与 derive jar 不同批上线：新前端要 token、旧 jar 不回传 | **必须成套部署**（同 STATUS 既有口径：jar+dist 随行）；上线顺序先 jar 后 dist |
| 旧浏览器 localStorage 无 token 但 Cookie 仍在 | checkAuth 无 token 判未登录 → 重登一次拿 token，自动愈合 |
| Cookie 不带 `Secure/SameSite`（现状缺陷） | 本方案不顺手扩 scope；若后续要修，作为 Cookie 兼容通道的独立小改（HTTPS 环境应补 `Secure`+`SameSite=Lax`） |
| token 过期后各页 A0003 报错 | 沿用现状（DeriveShell 注释既定行为：A0003 由登录门兜底），不新增拦截逻辑 |

## 四、验收口径（实施时按此执行）

1. **编译/构建**：`mvn -q compile` + `mvn -q test`（LoginVO 构造点全仓仅 4 处，改后无测试直接引用）；`cd web && npm run build`。
2. **本地实弹（8091）**：
   - `POST /api/auth/login` 响应体 `data.token` 非空；
   - `GET /api/derive/runs` 带 `-H 'Authorization: Bearer <token>'` → 200；
   - 不带头不带 Cookie → A0003/401；
   - 旧形态 `-b 'derive_token=...'` → 仍 200（兼容通道）。
3. **浏览器黄金路径**：登录 → F12 每个 `/derive/api/**` 请求头含 `Authorization: Bearer …` → runs/书库列表正常 → 退出再登录正常。
4. **宝塔上线**：需用户当轮明示授权后，jar+dist 成套推，线上复跑 2-3 项。
5. 收尾回写 `docs/STATUS.md`；未经允许不 commit/push。

## 五、实施任务拆分（获批后执行）

- T1 后端：LoginVO + 两个 AuthController（改动 #1-#3）→ 编译+单测
- T2 前端：deriveApi.js + DeriveShell.vue + 注释（改动 #4-#6）→ npm build
- T3 验收：本地 8091 实弹三口径 + 浏览器实测 → 回写 STATUS（部署单列、等授权）
