# 代码规约

> 适用：zzdzz-novel-generate-mvp 全部 Java/Vue 代码。与 `docs/git-commit-standards.md`（提交规约）并行生效。
> 核心模式：**MVC 分层开发**。所有新代码必须遵守；既有管线代码按 §8 的迁移路径渐进归位，不阻塞功能交付。

## §1. 分层模式与依赖方向

```
入口层   controller/（Web REST、SSE）  runner/（CLI ApplicationRunner）
   ↓ 只做：参数校验、调用 service、组装响应
业务层   service/（业务逻辑、事务边界、状态机推进、管线节点）
   ↓ 只做：业务编排
数据层   dao/（全部 SQL 唯一容身处）
   ↓
DB      PostgreSQL（is_deleted 软删除，见共识文档 §二#4）

model/   entity(DO) / dto / vo  贯穿各层，依赖方向单向向下，禁止反向与跨层
```

**铁律**：
1. Controller/Runner 禁止出现业务逻辑与 SQL；Service 禁止出现拼 SQL 与 HTTP 依赖；DAO 禁止出现业务判断。
2. 依赖只能 `入口 → service → dao`；`llm/`、`infra/` 是基础设施，service 可调用，但业务代码不得绕过 `LlmPort` 直接依赖 MiniMax 实现类。
3. 跨层传参：入口层收 DTO、出 VO；**DO 禁止逸出 service 层**（不给前端看表结构）。

## §2. 包结构

```
com.zzdzz.novelgen
├── controller/     # Web 入口：XxxController（REST/SSE）
├── runner/         # CLI 入口：XxxRunner（ApplicationRunner，视同 controller 层禁令）
├── service/        # 业务：XxxService（含管线节点：OutlineService、GateService…）
├── dao/            # 数据访问：XxxDAO（JdbcTemplate，MVP 不引 JPA）
├── model/
│   ├── entity/     # XxxDO：与表一一对应
│   ├── dto/        # 入参：XxxRequest / XxxDTO
│   └── vo/         # 出参：XxxResponse / XxxVO
├── llm/            # LLM 基础设施：LlmPort、MiniMaxClient、调用台账
├── pipeline/       # 连跑编排（组合各 service，AutoRunRunner 在 runner/）
├── config/         # @Configuration、属性类
└── common/         # Result 统一返回体、BizException、全局异常处理、工具类
```

前端 `web/` 对齐：`api/`（axios 封装）、`views/`（页面）、`components/`、`stores/`（Pinia）、`router/`。

## §3. 命名规约（基准：阿里巴巴《Java 开发手册》+ 命名参考，2026-09-10 采纳）

### 3.1 基础命名

| 类型 | 约束 | 例 |
|---|---|---|
| 项目名 | 全小写，中划线分隔 | novel-generate-mvp |
| 包名 | 全小写、单数、点分 | com.zzdzz.novelgen |
| 类名 | 大驼峰，名词或名词短语 | ChapterPipelineService |
| 方法/变量 | 小驼峰，动词或动宾短语 | listChapters / approveChapter |
| 常量 | CONSTANT_CASE 全大写下划线（局部常量可小驼峰） | MAX_REVISION_ROUNDS |

### 3.2 类名后缀（强制）

| 类型 | 后缀/前缀 | 例 |
|---|---|---|
| MVC 分层 | Controller / Service / ServiceImpl / DAO 后缀 | ChapterController、ChapterDAO |
| 领域模型 | DO / DTO / VO 后缀（**禁止 UserDo、UserDao 这类大小写混写**） | ChapterDO、ChapterDTO、ChapterVO |
| 枚举 | Enum 后缀 | ChapterStatusEnum |
| 工具类 | Utils 后缀 | StyleMetricsUtils |
| 异常 | Exception 结尾 | BizException |
| 测试类 | Test 结尾 | GateServiceTest |
| 抽象类 | Abstract / Base 开头 | AbstractGateRule |
| 接口实现类 | 接口名 + Impl（出现第二实现时才拆） | OutlineServiceImpl |
| 设计模式 | 模式名后缀 | ChapterPipelineFactory |
| 处理器/校验器 | Handler / Validator 后缀（配套方法 handle / validate） | RuleValidator |

### 3.3 方法命名

| 场景 | 前缀/后缀 | 例 |
|---|---|---|
| 返回真伪值 | is / can / should / has / needs | isValid、hasForeshadow |
| 检查并报错 | ensure / validate | validateOutline |
| 尝试执行 | try 前缀 / OrDefault、IfNeeded 后缀 | tryParse、getOrDefault |
| 异步 | Async / Sync 后缀，schedule / execute / cancel 前缀 | draftAsync、cancelJob |
| 回调 | on / before / after 前缀 | onGateFailed |
| 数据操作 | create / update / delete / save / load / find | findChapter、saveDraft |
| 集合操作 | add / remove / contains / find | addScene |

- 成对动词保持对称（get/set、add/remove、start/stop、import/export…）。
- 禁止 `process()`、`doHandle()` 这类无信息方法名。

### 3.4 变量与常量

- 变量小驼峰，禁下划线/`$` 开头，避免单字符（循环变量除外）。
- POJO 布尔字段不加 `is` 前缀（防序列化异常）；**数据库布尔列保留 `is_` 前缀**（本项目 `is_deleted` ✓）。
- 常量不要因怕长而缩写：`USER_MESSAGE_CACHE_EXPIRE_TIME` 优于 `MESSAGE_CACHE_TIME`。

### 3.5 通用禁令

- 禁止拼音，杜绝拼音英文混用（`validateCanShu` 反例）。
- 不与 JDK/框架已有类重名，不用 Java 关键字。
- 介词缩写（User4RedisDO、convertJson2Map）可用但需团队一致，MVP 阶段不使用。

### 3.6 模型层细则（model/）

**DO（entity/）**：与表一一对应，共 10 张表 10 个 DO，MVP 用 **record**（不可变，构造即完整）：

`UserDO / NovelDO / StylePackDO / CanonDocDO / ForeshadowDO / ChapterDO / SceneDO / DigestDO / GateReportDO / LlmCallLogDO`

- 字段 camelCase 对应表列；`is_deleted/create_time/update_time/delete_time` 一并建模
- **只能由 DAO 的 RowMapper 构造**，禁止业务代码 new DO 后绕过 DAO 改库
- **DO 不出 service 层**：DAO 返回 DO 给 service 是终点，跨层出参一律 VO

**DTO（dto/）与 VO（vo/）**：**类名必须以 `DTO` / `VO` 后缀结尾**；随首个 Web 接口引入，不预建空壳——每个接口按契约定义；VO 禁止直接序列化 DO。

**内部模型**：service 私有 record（如场景规格、LLM 请求体）允许存在，属实现细节，不跨层、不进 model/。

### 3.7 注解规约

- 每个类、公开方法必须有 javadoc（说明做什么/注意什么）；参数与返回值复杂的补 `@param/@return`。
- 禁止废话注解（`// 根据id获取信息` 配 `getMessageById(id)`）。
- 禁止行尾注释；`//` 或 `/*` 后空一格。
- 复杂包补 `package-info.java` 说明包职责。

## §4. 各层职责细则

| 层 | 必须做 | 禁止 |
|---|---|---|
| Controller / Runner | 参数校验（@Valid 或手动）、调 service、包 `Result<T>`、声明开放路径 | 业务逻辑、SQL、返回 DO、吞异常 |
| Service | 业务规则、状态机推进（`UPDATE…WHERE status=前置` 抢占）、事务边界、调 LlmPort 并落台账、写门禁/摘要 | 拼接 SQL、依赖 HttpServletRequest、持有可变单例状态 |
| DAO | SQL 全部集中于此、软删条件 `is_deleted=false` 必带、预编译参数 | 业务判断、调其他 DAO（组合留给 service） |

**SQL 规约**：
- 一律预编译参数（`?`），禁止字符串拼接；JSONB 入参用 `?::jsonb`。
- `update_time` 由 SQL 内 `NOW()` 维护，应用不手传时间。
- 状态机抢占必须是条件更新并检查影响行数（影响 0 行 = 并发冲突，报冲突而非静默）。
- **查询条件超过 3 个时建 `XxxQueryDTO`**（dto/）：controller 组装查询条件 → DAO 接收 DTO 拼 WHERE；
  2-3 个简单参数直接用方法签名裸参数，不造空壳。**查询结果一律返回 DO**，不在 DAO 造 VO。

## §5. 统一返回体与异常

```java
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static Result<Void> fail(int code, String message) { return new Result<>(code, message, null); }
}
```

- 业务异常抛 `BizException(code, message)`；`@RestControllerAdvice` 全局兜底，未知异常统一 500 + 不泄露堆栈。
- 错误码分段：`1xxx` 参数、`2xxx` 业务规则、`3xxx` 管线状态冲突、`5xxx` 系统/LLM。
- LLM 调用失败已由 `MiniMaxClient` 落 `llm_call_log`（含 error 行），service 层捕获 `LlmException` 后按管线语义处理（断点保留/重试），禁止静默吞掉。

## §6. 事务与并发

- 一个 service 方法 = 一个事务边界（`@Transactional`），**LLM 调用不得在事务内**（长任务占连接；先生成、后短事务落库）。
- 章状态推进：条件更新抢占，0 行命中即抛 `BizException(3xxx)`。
- 场景级缓存：已 `PASSED` 的场景重跑时跳过（幂等），写操作必须幂等或带前置条件。

## §7. 日志与测试

- 日志：关键节点 INFO（带 chapter/scene/token 上下文）、失败 WARN/ERROR；禁止打印 api_key、用户密码、正文全量（正文落库不落日志）。
- 测试：门禁指标、JSON 容错解析等**纯函数必须有单测**（参照 `MiniMaxClientExtractTest`，不烧 API）；DAO 写集成测试连本地库；每个里程碑交付附验收命令。

## §8. 既有代码迁移路径（本次已执行）

| 现状 | 去向 | 说明 |
|---|---|---|
| `canon/ImportRunner` | `runner/ImportRunner` | SQL 下沉各 DAO |
| `pipeline/AutoRunRunner` | `runner/AutoRunRunner`（薄）+ `service/ChapterPipelineService` | 编排逻辑归 service |
| `smoke/SmokeRunner` | `runner/SmokeRunner` | 开发冒烟入口 |
| `outline/OutlineNode` | `service/OutlineService` | SQL 下沉 `ChapterDAO`/`SceneDAO` |
| `generate/ContextPacker` | `service/ContextPackerService` | 读库下沉各 DAO |
| `generate/SceneGenerator` | `service/SceneService` | SQL 下沉 `SceneDAO` |
| `gate/MechanicalGate` | `service/GateService` | 指标计算保持静态纯函数；指纹读取下沉 `StylePackDAO` |
| `state/DigestNode` | `service/DigestService` | SQL 下沉 `DigestDAO`/`ForeshadowDAO` |
| `llm/MiniMaxClient` 内联 INSERT | `dao/LlmCallLogDAO` | 台账 SQL 归位 |
| `llm/*` 其余 | 不动（基础设施） | — |

model/entity/ 十个 DO 与上表同时落地。

### 8.1 2026-09-10 下午增量（Web 骨架落地）

- 包结构新增：`controller/`（5 个 REST）、`config/`（WebConfig：拦截器+CORS）、`common/web/`（Result/BizException/ErrorCode/GlobalExceptionHandler/AuthInterceptor）
- 管线新增：场景级断点复用（已 PASSED 场景跳过）、章级机械门禁修订轮（1 轮，带长度护栏）、存量过检正文复用、digest 幂等
- 迁移 V3__seed_admin：admin 种子（sha256(username:password)），上线前必须改密
- 已知口径：LLM 读超时 600s（章级修订实测 484s/48K tokens）
- LlmLogService 展示口径：详情接口 reasoningText（think 区）与 content（正文区，剥 `<think>` 后）分区返回

## §9. 前端补充（web/）

- 页面只调 `api/` 封装的 axios 方法；组件内不出现裸 URL。
- SSE 端点独立封装（EventSource），断线重连必须处理。
- Vue 组件用 `<script setup lang="ts">`；接口类型在 `api/types.ts` 与后端 VO 对齐。
