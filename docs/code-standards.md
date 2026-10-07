# 代码规约

> 适用：zzdzz-novel-generate-mvp 全部 Java/Vue 代码。与 `docs/git-commit-standards.md`（提交规约）并行生效。
> 核心模式：**MVC 分层开发**。所有新代码必须遵守；既有管线代码按 §8 的迁移路径渐进归位，不阻塞功能交付。
> **命名配对（现行）**：**DO=库实体（`model/entity/`）/ DTO=入参（`model/dto/`）/ VO=出参（`model/vo/`）**。
> 2026-09-21 曾被改为「DTO=库实体、DO 废除」，2026-10-03 按用户定调**回退**（原规则原文、逐条对照与落地范围见 §8.4；变更流水见 §0）。

## §0. 规约变更记录（只追加，不改写历史）

**为什么要这一节**：2026-09-21 那次命名改名（提交 `8df2bed`）**把代码和本规约放在同一个提交里一起改**——规约被重写成与新代码一致，于是原规则在仓库里失去了独立见证，看上去像「从来没存在过」。用户后来追问「DO 去哪了」，才靠 `git show 8df2bed^:docs/code-standards.md` 才把原文捞回来。**规则变更必须留下可独立追溯的记录，不能靠覆盖正文来实现。**

**变更流水（倒序）**：

1. **2026-10-08｜Mapper 文件夹统一更名（用户定调：「之后放 xxxMapper 这种文件的文件夹统一都叫 mapper」）**：`com.zzdzz.novelgen.dao` 包整体更名 `com.zzdzz.novelgen.mapper`——目录 `novelgen-data/.../dao` → `mapper`，93 个文件字面替换（Mapper 类的 package 行、全部 import、27 个 XML 的 namespace、启动类 `@MapperScan`），全库 `novelgen.dao` 引用 0 残留；`resources/mapper`（XML 目录）本就叫 mapper，两侧自此同名。**规约**：Mapper 类所在文件夹/包一律 `mapper`，不再新增 `dao` 命名；口头讲分层仍可说「dao 层」，但落码、路径、包名一律 mapper。历史流水与 §8 记录里的 `dao/` 字样按「只追加不改写」保留原貌。证据：`mvn clean test` 全绿（6 模块 56 测试类）。
2. **2026-10-08｜Maven 多模块拆分（用户定调「先把当前代码抽成多模块，基建/hook/规约弄好」）**：单模块拆为 6 模块（common/model/data/llm/service/app），依赖方向见 §1.1，编译期强制。三处逆向边的处置：①`FingerprintMetricVO` 对 `GateService.metricLabel` 的引用 → 中文名映射下沉 `model/vo/MetricLabels`（GateService 改静态导入）；②`dao`/`llm` 对 `service.data` 的行为依赖 → `service/data` 整体与 `dao` 合并进 `novelgen-data` 模块（层内自洽，行 record 无需搬家）；③`LlmJson`/`PromptCatalog` 对 `PromptTemplateService` 的依赖 → 该类物理下沉 `novelgen-llm`（包名 `service` 不变，零 import 改动）。**方式沿用本节约束**：先留流水与方案（`docs/改造方案-多模块与多书拼接.md`），代码单独落地。证据：每步 `mvn clean test` 全绿（终态 6 模块 354+ 条）、`mvn -DskipTests package` 出可执行 jar、`.githooks/pre-commit` 实跑通过（JDK17 环境被 enforcer 正确拦截后改钉 JDK21）。
3. **2026-10-03｜软删机制整体下线（用户定调：「把软删相关的设定全部从代码层清除」）**：删除一律**物理删除**。被推翻的原话是 §4「软删标记不进领域模型」「已知缺口（待收口）」两条，以及 §3.6「`is_deleted/...` 一并建模」——**"软删标记不进领域模型" 这条定调本身没被推翻，是被"整个软删都不要了"覆盖了**：既然不再软删，就没有标记该不该进模型的问题。落地范围：①XML 里 152 处 `is_deleted` 读过滤删除、Java 里 35 处 `.eq("is_deleted", false)` 删除；②6 个 `softDelete*` 语句改 `DELETE FROM`，方法名统一去 soft（`deletePlan`/`deleteCustom`/`deleteCard`/`deleteOrphanPack`…）；③`BaseDO` 去掉 `deleteTime`（只剩 `createTime/updateTime`）；④存量软删行一次性物理清洗（全库 30 张表软删行 0 行，整库备份 `var/purge-backup-20261003-201839.sql`）、活数据未动；⑤**新迁移 V37**：19 个外键加 `ON DELETE CASCADE`（父为 novels/chapters/imported_samples），否则硬删有内容的书会撞 `chapters_novel_id_fkey`——这是原先记在"已知缺口"里、被标为"必须拍板"的那个决定，本次拍板为级联。**刻意保留的两处**：库里 `is_deleted`/`delete_time` 两列与 8 个条件唯一索引留作死列/死谓词（同日定调「列留库里当死列，只清代码」）；3 处 `ON CONFLICT (...) WHERE is_deleted = FALSE` 必须原样保留（索引带谓词，去掉会报 no unique or exclusion constraint）。**前端同步**：6 处写着"软删，可恢复"的删除确认文案改口（否则会骗用户）；提示词页删掉那个恒为 false 的删除列开关。证据：`mvn test` 232/232、前端 `npm run build` 通过、26 个端点实弹 200、删书（级联）/删提示词/删素材卡三路实弹均物理消失、日志零异常。
4. **2026-10-03｜时间三件套上提 `BaseDO`（用户定调，同日其后被上一条收窄为两列）**：`createTime/updateTime/deleteTime` 由各实体改为基类统一持有（30 张业务表逐表核过三列俱在、且统一 `NOT NULL DEFAULT now()`）。此前 20 个实体各自手写、10 个根本没建模。**同一条「不进基类」的旧结论只对 `isDeleted` 继续成立**——软删标记不进领域模型这条铁律当时没变，变的只是它的邻座。被本次推翻的原话（§4「`BaseDO`」条、已按 §8.5 改写）："`isDeleted` 与时间戳刻意都不进基类"。**当时已记下**的代价：`update_time` 由 SQL 内 `NOW()` 维护，而 MP 更新策略是 NOT_NULL，所以「读出来→改字段→updateById」会把旧值写回 SET；现存两处 updateById 里 `ChapterStepDataServiceImpl.finish` 传新建 patch（时间列全 null，不进 SET）不受影响，`LlmProviderService.update` 命中该路径（既有问题，未修，修法见 §4）。落地：`/api/chapters/**` 等 17 个端点实弹 200、MP 生成 SQL 已带继承列、`mvn test` 232/232。
5. **2026-10-03｜回退命名配对（用户定调，已落地主体）**：恢复 `DO=库实体（model/entity）/ DTO=入参（model/dto）/ VO=出参（model/vo）`，撤销 2026-09-21 的「DTO=库实体、DO 后缀废除」。已落地：31 实体 `XxxDTO→XxxDO` 迁 `model/entity/`、7 个纯收参类入 `model/dto/`、`LoginVO` 拆成 `LoginDTO`+`LoginVO`。未落地：controller 内嵌的 23 个入参 record 提取归位（见 §8.4 进度）。**方式上的纠正**：本次先在本节留下流水、并在 §8.4 抄录原规则原文，再动代码——不再「先改代码、顺手把规约覆盖掉」。
6. **2026-09-21｜命名配对切换（提交 `8df2bed`，142 文件）**：`DO→DTO` 22 个实体并把 `model/entity/` 并进 `model/dto/`；27 个收参类 `XxxDTO→XxxVO`；同提交重写本规约 54 行 + `AGENTS.md` 铁律。**本次变更的问题不只是内容，而是方式**：正文被覆盖式改写、无变更记录，导致原规则不可追溯。
7. **2026-09-10｜规约首版**：基准为阿里巴巴《Java 开发手册》+ 命名参考，采用 `DO / DTO / VO` 三件套。

**流程约束（今后）**：改命名/分层这类规约，**同一提交内只允许改规约正文 + 本节追加一条流水**；代码迁移单独提交，提交信息里引用本节的条目号。禁止只改正文不留痕。

## §1. 分层模式与依赖方向

```
入口层   controller/（Web REST、SSE）  runner/（CLI ApplicationRunner）
   ↓ 只做：参数校验、调用 service、组装响应
业务层   service/（业务逻辑、事务边界、状态机推进、管线节点）
   ↓ 只做：业务编排
数据层   mapper/（原 dao，2026-10-08 更名；全部 SQL 唯一容身处）
   ↓
DB      PostgreSQL（删除＝物理删除；`is_deleted` 列留作死列，见 §4）

model/   entity(DO 库实体) / dto(入参) / vo(出参)  贯穿各层，依赖方向单向向下，禁止反向与跨层
```

**铁律**：
1. Controller/Runner 禁止出现业务逻辑与 SQL；Service 禁止出现拼 SQL 与 HTTP 依赖；DAO 禁止出现业务判断。
2. 依赖只能 `入口 → service → mapper`；`llm/`、`infra/` 是基础设施，service 可调用，但业务代码不得绕过 `LlmPort` 直接依赖 MiniMax 实现类。
3. 跨层传参：**入口层收 DTO、出 VO**；**DO 禁止逸出 service 层**（DAO 返回 DO 给 service 是终点，不给前端看表结构）。

### §1.1 Maven 多模块边界（2026-10-08 拆分，编译期强制）

```
novelgen-app ──→ novelgen-service ──→ novelgen-llm ──→ novelgen-data ──→ novelgen-model
     │                  │   └──────────────┴──────────────→ novelgen-common（叶子）
     │                  └─────────────────────────────────→ novelgen-model / novelgen-common
     └─ 含 controller/runner/config/启动类/web 三件套/application*.yaml（boot 打包只在 app）
novelgen-llm → novelgen-data（Resolver/MiniMaxClient 调数据接口）
novelgen-data = mapper/（原 dao，2026-10-08 更名）+ service/data/ + mapper XML + Flyway SQL（层内自洽，SQL 只在此模块）
```

**模块归属口诀**：包 `com.zzdzz.novelgen.X` 的代码物理上放 `novelgen-X`。以下三处**历史分裂**是拆分时为消逆向边做的定点搬迁，**新代码不得扩大分裂**：

1. `common/web` 包一半在 common（`BizException/ErrorCode/Result`），一半在 app（`AuthInterceptor/JwtService/GlobalExceptionHandler`——依赖 jjwt/LlmException/spring-webmvc，只能随启动层走）；
2. `PromptTemplateService` 物理在 llm 模块（包名仍是 `service`）——`LlmJson`/`PromptCatalog` 依赖它，放 service 层会成环；service/llm 两模块都可引它，方向合法；
3. 测试夹具 `fixtures/` 在 service 模块测试资源里，llm 模块自带一份 `digest_new_threads_nested_in_state.txt` 副本（两模块的测试都要读，test 资源不跨模块共享）。

**禁令**（违者编译期即报，新增依赖请先想清楚放哪层）：
- `model`/`common` 不得依赖任何业务模块；
- `service` 不得引 `controller`/`config`/`runner`（app 层）；
- `llm`/`data` 不得引 `service` 根包业务类（例外：上面第 2 条的 `PromptTemplateService`）。

**钩子**：`.githooks/pre-commit`（仓库内源头）→ 拷贝安装到 `.git/hooks/pre-commit`；跑 `mvn -q compile` 只挡编译错误，**不跑全量测试**（提交前自觉 `mvn test`）。钩子内无条件 `export JAVA_HOME=/d/Program/Java/jdk-21`——本机 Git Bash 环境常指向 jdk-17，enforcer 会拦（已实弹）。

## §2. 包结构

```
com.zzdzz.novelgen
├── controller/     # Web 入口：XxxController（REST/SSE）
├── runner/         # CLI 入口：XxxRunner（ApplicationRunner，视同 controller 层禁令）
├── service/        # 业务：XxxService（含管线节点：OutlineService、GateService…）
├── mapper/         # 数据访问：XxxMapper（MyBatis-Plus，SQL 在此类与 resources/mapper/*.xml；2026-10-08 由 dao/ 更名，规约：Mapper 文件夹一律叫 mapper）
├── model/
│   ├── entity/     # XxxDO：库实体，与表一一对应（mapper 层配对，@TableName）；**一律 extends BaseDO**（id + 时间三件套）
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

- 字段 camelCase 对应表列；`create_time/update_time` **由 `BaseDO` 统一提供，实体里不再重复声明**（见 §4）；`is_deleted`/`delete_time` **不建模**（软删机制已下线，两列留库当死列，见 §8.6）；JSONB/List 字段挂 TypeHandler + autoResultMap
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
| DAO | SQL 全部集中于此、预编译参数 | 业务判断、调其他 DAO（组合留给 service） |

**SQL 规约**：
- 一律预编译参数（`?`），禁止字符串拼接；JSONB 入参用 `?::jsonb`。
- `update_time` 由 SQL 内 `NOW()` 维护，应用不手传时间。
- **删除＝物理删除（2026-10-03 软删机制下线，见 §0 流水 1 与 §8.6）**：代码里**不再有** `is_deleted` 过滤、**不再有** `softDelete*` 方法（已改名 `delete*` 且真的 `DELETE FROM`）。库里 `is_deleted`/`delete_time` 两列与那些 `WHERE is_deleted=false` 的条件唯一索引保留当死列/死谓词，**不要在新代码里读写它们**。
- **唯一必须保留 `is_deleted` 的写法**：`ON CONFLICT (<cols>) WHERE is_deleted = FALSE`（现有 3 处：Embedding/WorldState/VolumeReview 的 upsert）。条件唯一索引还在，去掉谓词 PostgreSQL 会报 `no unique or exclusion constraint matching the ON CONFLICT specification`。
- **删父行要级联**：19 个外键（父为 `novels`/`chapters`/`imported_samples`）已加 `ON DELETE CASCADE`（迁移 V37），删书自动带走章节/场景/门禁报告/事实账/伏笔/世界状态/素材卡/正典/任务/事件/复盘。**没有级联外键的两处要显式清**：`embeddings`（无外键）与 `style_packs`（父表，删包不该牵走别的书）——见 `NovelService.deleteNovel` + `StylePackMapper.deleteOrphanPack`。
- **`BaseDO`（2026-10-03 抽，同日扩并收窄）**：30 个 XxxDO 一律 `extends BaseDO`。基类放**每张表都有、且每个实体都要建模的列**：`@TableId private Long id`（此前 30 份各自手写）+ `createTime/updateTime`（同日从 20 个实体上提，见 §0 流水 2 与 §8.5；`deleteTime` 曾一并上提、随软删下线移除，见 §8.6）。判定口径是「全表都有」而非「看着常用」——`novelId` 之类只在部分表里，不许进基类。
- **时间列进基类的已知代价（知悉并接受）**：`update_time` 由 SQL 内 `NOW()` 维护、应用不手传，而 MP 更新策略是 NOT_NULL——「读出来→改字段→updateById」会把读到的旧 `update_time` 写回 SET（表上无触发器，落库即旧值）。现存 updateById 只有两处：`ChapterStepDataServiceImpl.finish` 传新建 patch（时间列全 null → 不进 SET，安全）、`LlmProviderService.update`（命中，该行 `update_time` 不前进）。**修法（未做）**：给基类 `updateTime` 挂 `@TableField(update = "now()")`，让 MP 的 SET 直接写 `now()`。
- **投影查询里时间列保持 null**：只取部分列的 SQL 不填充它们，用前判空（如规划资产页的 content-only 查询只填 `CanonDocDO.content`）。
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
- **【当日晚些时候被 §8.5 扩展】**：上一条的「只含 id」已不成立——用户在「抽公共字段」的要求下把 `create_time/update_time/delete_time` 也放进基类。本条按 §0「只追加」规则保留原文，不代表现状。

### 8.4 2026-10-03 决定：回退 2026-09-21 的命名配对（用户定调，主体已落地）

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

### 8.5 2026-10-03 增量（时间三件套上提 `BaseDO`，用户定调）

**用户要求**：把 DO 里能抽出来的公共字段都挪进 `BaseDO`（点名「如时间等字段」）。**判定口径**：只抽「每张表都有」的列——`create_time/update_time/delete_time` 三列 30 张业务表逐表 `information_schema.columns` 核过俱在、且统一 `NOT NULL DEFAULT now()`（`delete_time` 可空）。`novelId`、`status` 这类只在部分表里的字段**不抽**。

**改动**：`BaseDO` 从「只有 `id`」变为「`id` + 三个 `OffsetDateTime`」；20 个各自手写过这三列的实体删掉重复声明（含随之无用的 `import java.time.OffsetDateTime`），10 个此前完全没建模的实体（`ChapterDO`/`DigestDO`/`ForeshadowDO`/`LlmModelPriceDO`/`LlmNodeConfigDO`/`MaterialCardDO`/`PresetCorpusDO`/`SceneDO`/`TuningDO`/`UserDO`）就此获得这三列。**`isDeleted` 不动**：软删标记不进领域模型这条铁律与本次互不冲突。

**为什么这次可以进基类（与被推翻的旧结论的差别）**：旧结论担心的唯一后果是「读出来→改字段→updateById 把旧 `update_time` 写回」，而实测全仓只有 2 处 updateById 调用点，其中 `ChapterStepDataServiceImpl.finish` 传的是新建 patch（时间列全 null，NOT_NULL 策略下不进 SET）。真正的命中点 `LlmProviderService.update` 是**既有问题**（该实体本来就声明了 `updateTime`，上提前后行为完全一致），不是本次引入。修法（未做）见 §4。

**风险与实弹**：MP 生成的 SQL 列清单会变（读路径多三列）、`INSERT` 不受影响（null → NOT_NULL 策略跳过 → 走 DB 默认值）。验证四层：`grep`（无实体再声明时间列）→ `mvn compile` → `mvn test` 232/232 → 重启实弹：17 个端点全 200、日志零异常，且打开 mapper DEBUG 后确认 MP 为 `ChapterDO` 生成的是 `SELECT id,novel_id,…,reject_reason,create_time,update_time,delete_time FROM chapters WHERE id=?`——**继承字段确实进了 SQL**（这一层必须看，否则「字段没被 MP 认下」会静默表现为读出来恒 null）。

**文档同步**：本节 + §0 流水 2 + §2 包结构 + §3.6 + §4「`BaseDO`」条 + `AGENTS.md` 铁律与坑 20。按 §0「流程约束」，**改规约与改代码分属两个提交**。

**【同日后被 §8.6 收窄】**：本节的 `deleteTime` 已随软删下线从 `BaseDO` 移除，只留 `createTime/updateTime`。本节按「只追加」规则保留原文。

### 8.6 2026-10-03 增量（软删机制整体下线，用户定调）

**用户要求**：原话「那把软删相关的设定全部从代码层清除，提示词这个页面不暴露删除按钮」。触发点是当天早些时候的一次核查：软删的**写**是好的（`SET is_deleted=true, delete_time=NOW()`），但**读**是半瘫的——手写 XML 都带 `is_deleted = FALSE`（挡住了），而 MP 自生成的 SQL 带不了，实弹两例：`GET /api/chapters/338` 返回了已软删章节的完整正文、`GET /api/novels/42/derive-config` 返回了已软删书的配置；列表接口反而是干净的，所以表面上「看着正常」，漏点全在"按 id 取单个"的路径上（`getById` 打在软删实体上约 17 处）。结论是**与其留一个半瘫的软删，不如整体下线**。

**落地范围**：
1. 读过滤：XML 152 处 `is_deleted` 谓词、Java 35 处 `.eq("is_deleted", false)` 全部删除。**踩到的坑**：脚本按行删谓词时，遇到「`WHERE is_deleted = FALSE` 是语句里唯一的 WHERE、后面还跟着 `AND (...)`」会删出缺 WHERE 的破碎 SQL（`ChapterMapper.listPlanRows`、`LlmCallLogMapper` 的 findPage/countBy/totalsBy 共 4 处），MyBatis 启动不报、执行才炸——已逐条修回 `WHERE (...)`，并写了 `var/check_sql_structure.py` 做语句块级自检。
2. 写路径：6 个 `softDelete*` 语句改 `DELETE FROM`（canon_docs / chapters / llm_node_config / material_cards / prompt_templates / style_packs），方法名去 soft 统一为 `delete`/`deletePlan`/`deleteCustom`/`deleteCard`/`deleteBySample`/`deleteByLevel`/`deleteOrphanPack`。
3. `BaseDO` 去掉 `deleteTime`。
4. **存量清洗**：30 张表软删行 + 软删书的关联树（书软删时未级联标记下属行，故 28 本软删书名下 95 章等约 300 行也要清）一次性物理删除，共约 1000 行；整库备份 `var/purge-backup-20261003-201839.sql`（183MB），清洗后活数据实测未动（15 本书、book 1 的 30 章、723 条门禁报告、1784 条 llm_call_log 全在）。**清洗脚本差点犯的错**：章族子表用 `chapter_id IN (SELECT id FROM chapters)` 是未加限制的全表——会把活书的门禁报告一起删掉；改成显式目标集（软删章 ∪ 软删书名下的章）后才执行。
5. **迁移 V37**：给 19 个外键（父为 `novels`/`chapters`/`imported_samples`）加 `ON DELETE CASCADE`。不加级联的话 `DELETE FROM novels WHERE id=?` 会被 `chapters_novel_id_fkey` 挡住（AGENTS.md 里记的「必须拍板」那条，本次拍板为级联）。`style_packs`/`users` 作父的三个外键**刻意不加**——删包/删用户不该牵走别的业务实体。
6. 前端：6 处「软删，可恢复」的删除确认文案改口（`BooksView`/`WizardView`/`LibraryView` ×2 等）——不改就是骗用户；提示词页那个恒为 `false` 的 `showPromptDelete` 开关连同删除列、`deletePrompt` 死代码一并去掉，**后端 `DELETE /api/prompts/{id}` 保留**（用户明确要求只去 UI）。

**刻意保留**：`is_deleted`/`delete_time` 两列与 8 个 `WHERE is_deleted=false` 条件唯一索引留库当死列/死谓词（同日定调「列留库里当死列，只清代码」，不写迁移删列）；**3 处 `ON CONFLICT (...) WHERE is_deleted = FALSE` 必须原样保留**——索引谓词还在，去掉会报 `no unique or exclusion constraint matching the ON CONFLICT specification`。

**证据**：`mvn test` 232/232（两个 NovelService 测试的构造器与断言跟着改）、前端 `npm run build` 通过、26 个端点实弹 200（另两个非 200 是我自己写错的 URL：缺 `novelId` 参数、路径不存在）、日志零异常；三路删除实弹——删书（书/章/卡/正典/风格包/向量全为 0，级联生效）、删自定义提示词、删素材卡，均物理消失且全库 `is_deleted=true` 为 0 行。

**文档同步**：本节 + §0 流水 1 + §1 图 + §3.6 + §4 全部软删条目 + `AGENTS.md`（铁律「数据库」条、坑 9/11/20、端点速查）+ `docs/STATUS.md`。按 §0「流程约束」，**规约改动与代码改动分属两个提交**。`docs/architecture/pipeline-contracts.md §六` 的四条口径按「删除＝真删」重读。

## §9. 前端补充（web/）

- 页面只调 `api/` 封装的 axios 方法；组件内不出现裸 URL。
- SSE 端点独立封装（EventSource），断线重连必须处理。
- Vue 组件用 `<script setup lang="ts">`；接口类型在 `api/types.ts` 与后端 VO 对齐。
