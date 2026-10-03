# 代码规约

> 适用：zzdzz-novel-generate-mvp 全部 Java/Vue 代码。与 `docs/git-commit-standards.md`（提交规约）并行生效。
> 核心模式：**MVC 分层开发**。所有新代码必须遵守；既有管线代码按 §8 的迁移路径渐进归位，不阻塞功能交付。
> **命名配对（现行）**：**DO=库实体（`model/entity/`）/ DTO=入参（`model/dto/`）/ VO=出参（`model/vo/`）**。
> 2026-09-21 曾被改为「DTO=库实体、DO 废除」，2026-10-03 按用户定调**回退**（原规则原文、逐条对照与落地范围见 §8.4；变更流水见 §0）。

## §0. 规约变更记录（只追加，不改写历史）

**为什么要这一节**：2026-09-21 那次命名改名（提交 `8df2bed`）**把代码和本规约放在同一个提交里一起改**——规约被重写成与新代码一致，于是原规则在仓库里失去了独立见证，看上去像「从来没存在过」。用户后来追问「DO 去哪了」，才靠 `git show 8df2bed^:docs/code-standards.md` 才把原文捞回来。**规则变更必须留下可独立追溯的记录，不能靠覆盖正文来实现。**

**变更流水（倒序）**：

1. **2026-10-03｜回退命名配对（用户定调，已落地主体）**：恢复 `DO=库实体（model/entity）/ DTO=入参（model/dto）/ VO=出参（model/vo）`，撤销 2026-09-21 的「DTO=库实体、DO 后缀废除」。已落地：31 实体 `XxxDTO→XxxDO` 迁 `model/entity/`、7 个纯收参类入 `model/dto/`、`LoginVO` 拆成 `LoginDTO`+`LoginVO`。未落地：controller 内嵌的 23 个入参 record 提取归位（见 §8.4 进度）。**方式上的纠正**：本次先在本节留下流水、并在 §8.4 抄录原规则原文，再动代码——不再「先改代码、顺手把规约覆盖掉」。
2. **2026-09-21｜命名配对切换（提交 `8df2bed`，142 文件）**：`DO→DTO` 22 个实体并把 `model/entity/` 并进 `model/dto/`；27 个收参类 `XxxDTO→XxxVO`；同提交重写本规约 54 行 + `AGENTS.md` 铁律。**本次变更的问题不只是内容，而是方式**：正文被覆盖式改写、无变更记录，导致原规则不可追溯。
3. **2026-09-10｜规约首版**：基准为阿里巴巴《Java 开发手册》+ 命名参考，采用 `DO / DTO / VO` 三件套。

**流程约束（今后）**：改命名/分层这类规约，**同一提交内只允许改规约正文 + 本节追加一条流水**；代码迁移单独提交，提交信息里引用本节的条目号。禁止只改正文不留痕。

## §1. 分层模式与依赖方向

```
入口层   controller/（Web REST、SSE）  runner/（CLI ApplicationRunner）
   ↓ 只做：参数校验、调用 service、组装响应
业务层   service/（业务逻辑、事务边界、状态机推进、管线节点）
   ↓ 只做：业务编排
数据层   dao/（全部 SQL 唯一容身处）
   ↓
DB      PostgreSQL（is_deleted 软删除，见共识文档 §二#4）

model/   dto(库实体) / vo(web 出入参)  贯穿各层，依赖方向单向向下，禁止反向与跨层
```

**铁律**：
1. Controller/Runner 禁止出现业务逻辑与 SQL；Service 禁止出现拼 SQL 与 HTTP 依赖；DAO 禁止出现业务判断。
2. 依赖只能 `入口 → service → dao`；`llm/`、`infra/` 是基础设施，service 可调用，但业务代码不得绕过 `LlmPort` 直接依赖 MiniMax 实现类。
3. 跨层传参：**入口层收 DTO、出 VO**；**DO 禁止逸出 service 层**（DAO 返回 DO 给 service 是终点，不给前端看表结构）。

## §2. 包结构

```
com.zzdzz.novelgen
├── controller/     # Web 入口：XxxController（REST/SSE）
├── runner/         # CLI 入口：XxxRunner（ApplicationRunner，视同 controller 层禁令）
├── service/        # 业务：XxxService（含管线节点：OutlineService、GateService…）
├── dao/            # 数据访问：XxxMapper（MyBatis-Plus，SQL 在此类与 resources/mapper/*.xml）
├── model/
│   ├── entity/     # XxxDO：库实体，与表一一对应（mapper 层配对，@TableName）；**一律 extends BaseDO**（只含 id）
│   ├── dto/        # 入参：XxxRequest / XxxDTO（如 XxxQueryDTO）
│   ├── enums/      # 状态枚举（wire() 返回落库字符串原值）
│   └── vo/         # 出参：XxxResponse / XxxVO
├── llm/            # LLM 基础设施：LlmPort、MiniMaxClient、调用台账
├── pipeline/       # 连跑编排（组合各 service，AutoRunRunner 在 runner/）
├── config/         # @Configuration、属性类
└── common/         # Result 统一返回体、BizException、全局异常处理、工具类
```

前端 `web/` 对齐：`api/`（axios 封装）、`views/`（页面）、`components/`、`stores/`（Pinia）、`router/`。

## §3. 命名规约（基准：阿里巴巴《Java 开发手册》+ 命名参考，2026-09-10 采纳；**2026-09-21 配对修订**：DTO=库实体、VO=web 出入参，见文首）

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

**DO（entity/）=库实体**：与表一一对应（清单以 `model/entity/` 现状为准），MyBatis-Plus `@Data + @TableName`。原规约的「MVP 用 record、只能由 DAO 的 RowMapper 构造」两条自 2026-09-13 数据层切 MyBatis-Plus 后已不适用（MP 的写入依赖无参构造 + setter），**其余（与表一一对应、DO 不出 service）不变**：

- 字段 camelCase 对应表列；`is_deleted/create_time/update_time/delete_time` 一并建模；JSONB/List 字段挂 TypeHandler + autoResultMap
- 只经 mapper 写入（BaseMapper / 自定义语句），禁止业务代码绕过数据层改库
- **库实体不出 service 层**：mapper 返回 DTO 给 service 是终点，跨层出参一律 VO（service 内 `from()` 转换）

**VO（vo/ 及 controller 内嵌）=web 层出入参**：**类名必须以 `VO` 后缀结尾**（收参与返回同规）；随首个 Web 接口引入，不预建空壳；返回侧 VO 禁止直接序列化库实体（软删除三件套等内部字段不出 API）。

**内部模型**：service 私有 record（如场景规格、LLM 请求体、查询投影 TaskRow 等）允许存在，属实现细节，不跨层、不进 model/。

### 3.7 注解规约

- 每个类、公开方法必须有 javadoc（说明做什么/注意什么）；参数与返回值复杂的补 `@param/@return`。
- 禁止废话注解（`// 根据id获取信息` 配 `getMessageById(id)`）。
- 禁止行尾注释；`//` 或 `/*` 后空一格。
- 复杂包补 `package-info.java` 说明包职责。

## §4. 各层职责细则

| 层 | 必须做 | 禁止 |
|---|---|---|
| Controller / Runner | 参数校验（@Valid 或手动）、调 service、包 `Result<T>`、声明开放路径 | 业务逻辑、SQL、返回库实体（XxxDTO）、吞异常 |
| Service | 业务规则、状态机推进（`UPDATE…WHERE status=前置` 抢占）、事务边界、调 LlmPort 并落台账、写门禁/摘要 | 拼接 SQL、依赖 HttpServletRequest、持有可变单例状态 |
| DAO | SQL 全部集中于此、软删条件 `is_deleted=false` 必带、预编译参数 | 业务判断、调其他 DAO（组合留给 service） |

**SQL 规约**：
- 一律预编译参数（`?`），禁止字符串拼接；JSONB 入参用 `?::jsonb`。
- `update_time` 由 SQL 内 `NOW()` 维护，应用不手传时间。
- **软删标记不进领域模型（2026-10-03 定调）**：**实体上不声明 `isDeleted`**，也**不开** MP 的全局逻辑删除（`global-config.db-config.logic-delete-field` 已移除）。软删条件只由 DAO 的 SQL 自己带：**手写 XML 里 `is_deleted = FALSE` 必须逐条写**——这是唯一的过滤点，漏一条那一条就读得到软删行。**禁止任何查询刻意读取软删行**（`StylePackMapper.findReusablePackId` 曾刻意捞已软删的包来复用，已按此口径去掉）。
- **已知缺口（待收口）**：MP 自己生成的 SQL（`BaseMapper`/`IService`/`Wrapper`，本项目约 43 个调用点）在没有实体字段的情况下**无法自动过滤软删行**，`getById` 会取到软删行（`NovelService.requireNovel`、`GenerationQueueService.requireNovelTitle` 这类判空守卫因此对已软删的 id 失效）。要让「软删行不可见」在这个前提下继续成立，只能把软删行**真正删掉**（代码走硬删 + 存量软删行清洗），那是另一件事，未做。
- **`BaseDO`（2026-10-03 抽）**：31 个 XxxDO 一律 `extends BaseDO`，基类**只放每张表都有的一列**——`@TableId private Long id`（此前 31 份各自手写）。**`isDeleted` 与时间戳刻意都不进基类**：前者是不进领域模型的软删标记；后者是 `update_time` 由 SQL 内 `NOW()` 维护，进了基类后任何「查出来→改字段→updateById」都会把旧值写回去覆盖 NOW()（MP 更新策略是 NOT_NULL，非空字段都进 SET）。
- 状态机抢占必须是条件更新并检查影响行数（影响 0 行 = 并发冲突，报冲突而非静默）。
- **查询条件超过 3 个时建 `XxxQueryDTO`**（`model/dto/`）：controller 组装查询条件 → DAO 接收 DTO 拼 WHERE；2-3 个简单参数直接用方法签名裸参数，不造空壳。**查询结果一律返回 DO 或类型化投影 record**（随 DataService 声明），不在 mapper 造 VO。

## §5. 统一返回体与异常

```java
public record Result<T>(String code, String message, T data,
                        @JsonInclude(JsonInclude.Include.NON_NULL) Object detail) {
    public static <T> Result<T> success(T data) { /* code="00000"，message="ok" */ }
    public static Result<Void> success() { /* 同上，data=null */ }
    public static <T> Result<T> fail(ErrorCode ec, String message) { /* 泛型化：失败值可用于任意 Result<T> 返回位 */ }
    public static <T> Result<T> fail(ErrorCode ec, String message, Object detail) { /* detail=失败结构化明细（实际状态/未过条目等），成功恒 null 且不序列化 */ }
}
```

- 业务异常抛 `BizException(ErrorCode, message[, detail])`；`@RestControllerAdvice` 统一转 Result 并按 ErrorCode 设 HTTP 状态，未知异常统一 500 + 不泄露堆栈。
- 错误码为 5 位字符串（成功 `"00000"`；A 用户 / B 系统 / C 第三方 / D 中间件 / E 其他，如 A0006），HTTP 语义挂在 ErrorCode 枚举上；前端 `api.js` 判 `code !== '00000'`，失败异常附 code/detail。
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

### 8.2 2026-09-21 增量（命名配对切换）

- 命名配对切为项目自有基线（见文首修订说明）：22 实体 `XxxDO→XxxDTO` 迁 `model/entity→model/dto`；27 个收参类 `XxxDTO→XxxVO`（4 顶层迁 model/vo + 23 个 controller 内嵌）；出参 VO 原名不动；AGENTS.md 铁律同步改写并指向本文档。
- 数据层自 09-13 起为 MyBatis-Plus：`dao/` 为 XxxMapper + `resources/mapper/*.xml`，数据服务在 `service/data`（DataService 接口 + impl）。

### 8.3 2026-10-03 增量（软删标记不进领域模型 + BaseDO 只留 id）

- 实体上不声明 `isDeleted`（撤销当天先加后撤的一次反复），MP 全局逻辑删除配置一并移除；软删条件只由 DAO 的 SQL 自己带（XML 里 `is_deleted = FALSE` 逐条写）。详见 §3「SQL 规约」。
- 31 个实体抽 `BaseDO`（只含 `@TableId id`）；`lombok.config` 加 `equalsAndHashCode.callSuper = call`。

### 8.4 2026-10-03 决定：回退 2026-09-21 的命名配对（用户定调，落地中）

**目标配对（恢复 2026-09-21 之前的原规则）**：`DO=库实体 / DTO=入参 / VO=出参`。

**原规则原文**（`git show 8df2bed^:docs/code-standards.md`，逐条抄录）：

```
model/   entity(DO) / dto / vo  贯穿各层，依赖方向单向向下，禁止反向与跨层
3. 跨层传参：入口层收 DTO、出 VO；DO 禁止逸出 service 层（不给前端看表结构）。
│   ├── entity/     # XxxDO：与表一一对应
│   ├── dto/        # 入参：XxxRequest / XxxDTO
│   └── vo/         # 出参：XxxResponse / XxxVO
| 领域模型 | DO / DTO / VO 后缀（禁止 UserDo、UserDao 这类大小写混写） | ChapterDO、ChapterDTO、ChapterVO |
DTO（dto/）与 VO（vo/）：类名必须以 DTO / VO 后缀结尾；随首个 Web 接口引入，不预建空壳；VO 禁止直接序列化 DO。
Controller/Runner 禁止：…返回 DO…
- 查询条件超过 3 个时建 XxxQueryDTO（dto/）：controller 组装查询条件 → DAO 接收 DTO 拼 WHERE；
  2-3 个简单参数直接用方法签名裸参数，不造空壳。查询结果一律返回 DO，不在 DAO 造 VO。
```

**逐条对照**：

| 项 | 原规则（2026-09-10 首版） | 2026-09-21 被改成 | 2026-10-03 决定 |
|---|---|---|---|
| 库实体 | `XxxDO`（`model/entity/`） | `XxxDTO`（`model/dto/`） | **回到 `XxxDO`** |
| 入参 | `DTO`（`model/dto/`，如 `XxxQueryDTO`） | web 收参一律 `VO` | **回到 `DTO`** |
| 出参 | `VO`（`model/vo/`） | `VO`（不变） | **`VO`（不变）** |
| 三件套形状 | 三个包各司其职，无孪生类 | 压成两个包（实体+web 出入参） | **回到三件套** |

**落地进度（2026-10-03）**：

- ✅ **库实体**：31 个 `XxxDTO` → `XxxDO`，`model/dto/` → `model/entity/`（含 `BaseDTO`→`BaseDO`）；引用面 155 个文件 856 处已改，`mvn clean test` 234/234、真库冒烟通过。
- ✅ **纯入参**：`model/vo/` 里 7 个纯收参类 → `model/dto/` 的 `XxxDTO`（`ApprovalModeDTO`/`NovelCreateDTO`/`NovelQueryDTO`/`PipelineRunDTO`/`PlanAssetQueryDTO`/`PlanModeDTO`/`StyleFingerprintQueryDTO`）。
- ✅ **既收又出的那一个**：`LoginVO(username, password)` 拆成 `model/dto/LoginDTO(username, password)`（入参）+ `model/vo/LoginVO(username, role)`（出参）。原类当响应体用时第二个位置塞的是 role，字段名与内容不符、且把口令字段带进了出参契约；前端请求体字段名不变。
- ⬜ **剩余**：controller **内嵌的 23 个入参 record**（`NovelController.NovelCreateVO` 这类）尚未归位到 `model/dto/`。这不是改名，是**提取重构**（内嵌 record 提到顶层 + 改各层引用），单独一批做。
- ⬜ **原规约 §3.4**「POJO 布尔字段不加 `is` 前缀（防序列化异常）」尚未逐条核对：已知 `StylePackDO.isPreset` 违反此条（应为 `preset`，列名 `is_preset` 不变）。
- ⬜ 原规约 §3.6 的「DO 用 record」不执行（与 MyBatis-Plus 冲突，见上）。

**待决**：原规则另有一条「DO 用 record（不可变，构造即完整）」——当前实体是 Lombok `@Data` + MyBatis-Plus（`save()`/`updateById()`/XML `resultType` 都依赖无参构造 + setter），改成 record 会动到 MP 的写入路径，属独立决定，本次不随改名一起做。若仍要执行，单独开一条。

## §9. 前端补充（web/）

- 页面只调 `api/` 封装的 axios 方法；组件内不出现裸 URL。
- SSE 端点独立封装（EventSource），断线重连必须处理。
- Vue 组件用 `<script setup lang="ts">`；接口类型在 `api/types.ts` 与后端 VO 对齐。
