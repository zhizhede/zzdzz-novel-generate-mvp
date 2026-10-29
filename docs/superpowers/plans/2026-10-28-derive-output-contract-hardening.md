# DeriveFlow 输出协议与可观测性 Implementation Plan

> **Superseded:** A1/A7 的历史版本已废弃。新的主执行计划见 [2026-10-29-derive-refactor-single-pass-plan.md](2026-10-29-derive-refactor-single-pass-plan.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. This plan is executable independently and must finish before the plan compiler work starts.

**Goal:** 建立节点级 LLM 输出协议，使 ROUTE 等 JSON 节点只产生叙事字段，所有结构字段由 Java 生成，同时把 HTTP 成功和内容合规彻底区分开。

**Architecture:** `NodeOutputContract` 统一承载 schema、枚举白名单、解析器、领域校验和字段路径错误；`LlmJson.askStructured` 只负责语法修复、schema/领域校验、带路径反馈和账本记录。ROUTE v2 删除 `beatRole` 等机械字段，状态字段改为“角色 key 一次 + 值数组”。

**Tech Stack:** Java 21、Jackson、JUnit 5、Mockito、Vue 3。

**Spec:** `docs/superpowers/plans/2026-10-28-derive-typed-plan-compiler-refactor.md`。

## Global Constraints

- 遵守 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md`。
- 未获用户当轮允许，禁止 commit/push/deploy。
- 不修改历史 Flyway；本子计划不新增数据库迁移。
- `LlmJson.read` 继续只处理 JSON 语法；业务 schema/领域校验放在 `NodeOutputContract`。
- 模型不得在 ROUTE v2 输出 `beatRole`、`chapterNo`、beat 自增身份或跨层引用 ID。
- 所有枚举白名单从 Java 枚举渲染，禁止手写第二份。
- 测试必须包含真实历史坏输出 replay，不只包含手工最小 JSON。
- 不修改主站 `novelgen`。

## Review Focus

- 未知枚举必须报告节点、字段路径、实际值和完整允许值。
- `requiredStateBefore/After` 同角色多事实必须用数组值，不能产生重复键。
- `status=ok` 但内容不合规时，UI 和读模型必须显示“内容拒绝”。
- 历史 run44 的重复 `C1` 和 run60 的 `OBSTACLE/MIDPOINT` 必须离线回放。
- 合法模型输出不能被错误归一或静默丢字段。

---

### Task A1: 建立有来源证明的 replay fixture loader

**Boundary:** 只建立可重复的历史回放样本、合成正样例和测试侧 loader；不修改生产 parser、LlmJson、ROUTE、数据库或前端。

**Why:** 后续协议修复必须能重复输入真实历史输出；合法新 schema 样例若必须来自历史账本会造成不可满足的输入，因此必须明确区分 `HISTORICAL` 与 `SYNTHETIC`。

**Inputs:**
- 只读 `llm_call_log.response_json->choices->0->message->content`；
- 历史 ID：run44 2149；run58 3087、3088、3089、3107、3108、3109；run60 3141、3142、3143；
- `route-valid.json` 必须明确为 `SYNTHETIC` 正样例，不能声称来自历史账本；
- fixture metadata 必须含 source kind、runId、callLogId、node、content hash；合成样例的 runId/callLogId/node 为 null。

**Outputs:**
- 10 个 fixture 文件；
- `ReplayFixture` / `ReplaySource`；
- `ReplayFixtureLoader`；
- `ReplayFixtureLoaderTest`；
- `var/task-a1/test-before.log`（实现前失败证据）。

**Pass Criteria:**
- 六条 run58 历史调用各有独立 fixture；
- 历史 fixture 的 source metadata 与导出账本一致；
- rawContent 逐字保存，MD5 与 source.contentMd5 一致；
- `route-valid.json` 标记为 `SYNTHETIC`；
- loader 测试通过；
- 负向 fixture 测试通过；
- `test-before.log` 包含首次失败命令和失败输出；
- 不修改 Files 之外的生产文件。

**Files:**
- Modify: `novelgen-service/src/test/resources/derive-replay/run44-c1-duplicate.json`
- Modify: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys.json`
- Create: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys-3088.json`
- Create: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys-3089.json`
- Create: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys-3107.json`
- Create: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys-3108.json`
- Create: `novelgen-service/src/test/resources/derive-replay/run58-head-c-no-clue-keys-3109.json`
- Modify: `novelgen-service/src/test/resources/derive-replay/run60-route-obstacle.json`
- Modify: `novelgen-service/src/test/resources/derive-replay/run60-route-c1-duplicate.json`
- Modify: `novelgen-service/src/test/resources/derive-replay/run60-route-midpoint.json`
- Modify: `novelgen-service/src/test/resources/derive-replay/route-valid.json`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/ReplayFixtureLoader.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/ReplayFixtureLoaderTest.java`
- Create: `var/task-a1/test-before.log`

**Interfaces:**
- Consumes: classpath JSON resources and a fixture source metadata envelope
- Produces:

```java
record ReplaySource(
        String kind,
        Long runId,
        Long callLogId,
        String node,
        String contentMd5) {}

record ReplayFixture(
        String id,
        String contractId,
        int schemaVersion,
        ReplaySource source,
        String rawContent) {}

ReplayFixture ReplayFixtureLoader.load(String resourcePath);
```

- [ ] **Step 1: 写失败测试 `loadsRun60ObstacleFixture`**

断言：

```java
fixture.id().equals("run60-route-obstacle")
fixture.contractId().equals("derive.route_narrative")
fixture.schemaVersion() == 2
fixture.source().kind().equals("HISTORICAL")
fixture.source().runId() == 60L
fixture.source().callLogId() == 3141L
fixture.source().node().equals("derive_plan")
fixture.rawContent().contains("\"OBSTACLE\"")
```

- [ ] **Step 2: 运行并确认失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=ReplayFixtureLoaderTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: 新 `ReplaySource` 不存在，测试失败。将本步骤命令输出保存到 `var/task-a1/test-before.log`。

- [ ] **Step 3: 导出历史 fixture**

逐条导出 2149、3087、3088、3089、3107、3108、3109、3141、3142、3143 的原始 content；不保存 API key、cookie 或请求头。

历史 envelope：

```json
{
  "fixtureId": "run58-3087-head-c",
  "contractId": "derive.head_c",
  "schemaVersion": 1,
  "source": {
    "kind": "HISTORICAL",
    "runId": 58,
    "callLogId": 3087,
    "node": "derive_draft",
    "contentMd5": "<真实 content 的 MD5>"
  },
  "rawContent": "<原样模型 content>"
}
```

`rawContent` 必须逐字保存，不能预先格式化或修复。

- [ ] **Step 4: 写合成正样例**

`route-valid.json` 使用：

```json
{
  "fixtureId": "route-v2-valid-synthetic",
  "contractId": "derive.route_narrative",
  "schemaVersion": 2,
  "source": {
    "kind": "SYNTHETIC",
    "runId": null,
    "callLogId": null,
    "node": null,
    "contentMd5": "<synthetic rawContent 的 MD5>"
  },
  "rawContent": "..."
}
```

它可以为新 ROUTE v2 手写，但不得冒充历史输出。

- [ ] **Step 5: 实现 loader 与负向测试**

loader 返回 `ReplaySource`；测试至少覆盖：

```java
void loadsHistoricalFixtureWithSource()
void loadsSyntheticFixtureWithNullSourceIds()
void rejectsBlankResourcePath()
void rejectsMissingResource()
void rejectsBlankFixtureId()
void rejectsNonPositiveSchemaVersion()
void rejectsInvalidJson()
```

- [ ] **Step 6: 再跑测试**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=ReplayFixtureLoaderTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS，且 `var/task-a1/test-before.log` 保留首次失败证据。

---

### Task A2: 定义 ContractViolation 与 NodeOutputContract

**Boundary:** 只定义跨节点的结构化错误和节点契约数据类型，不实现 registry、schema 或业务校验。

**Why:** 统一 error path、expected、actual，避免所有节点继续拼接模糊中文异常。

**Inputs:** contract id、schema version、prompt fragment、parser、domain validator 的类型需求。

**Outputs:** ContractViolation 和 NodeOutputContract 两个公开 record。

**Pass Criteria:** feedbackLine 含 code/path/expected/actual，定向测试通过。

**Files:**
- Create: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/ContractViolation.java`
- Create: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/NodeOutputContract.java`
- Create: `novelgen-llm/src/test/java/com/zzdzz/novelgen/llm/contract/ContractViolationTest.java`

**Interfaces:**
- Produces:

```java
public record ContractViolation(
        String code,
        String path,
        String message,
        String expected,
        String actual) {
    public String feedbackLine();
}

public record NodeOutputContract<T>(
        String contractId,
        String node,
        int schemaVersion,
        JsonNode schemaView,
        String promptFragment,
        Function<JsonNode, T> parser,
        Function<T, List<ContractViolation>> domainValidator) {}
```

- [ ] **Step 1: 写失败测试 `feedbackLineIncludesCodePathExpectedAndActual`**

输入：

```java
new ContractViolation("SCHEMA_ENUM_INVALID", "assignments[4].beatRole",
    "枚举非法", "HOOK|...|FINAL_CLOSURE", "OBSTACLE")
```

断言 `feedbackLine()` 同时包含四个值。

- [ ] **Step 2: 运行失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-llm -am "-Dtest=ContractViolationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: 类不存在。

- [ ] **Step 3: 实现 record**

`feedbackLine()` 使用固定格式：

```text
[code] path=... expected=... actual=...：message
```

- [ ] **Step 4: 再跑测试**

Expected: PASS。

---

### Task A3: 实现 NodeOutputContractRegistry

**Boundary:** 只管理 contract id 与 schema version 到契约对象的注册关系。

**Why:** 防止同一节点出现两个无版本区分的契约，保证启动期可以发现冲突。

**Inputs:** NodeOutputContract 实例。

**Outputs:** 可注册、按 id/version 查询、全量枚举的 NodeOutputContractRegistry。

**Pass Criteria:** 重复 key 启动失败，缺失 key 报明确异常，定向测试通过。

**Files:**
- Create: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/NodeOutputContractRegistry.java`
- Create: `novelgen-llm/src/test/java/com/zzdzz/novelgen/llm/contract/NodeOutputContractRegistryTest.java`

**Interfaces:**
- Produces:

```java
@Component
public class NodeOutputContractRegistry {
    public void register(NodeOutputContract<?> contract);
    public NodeOutputContract<?> require(String contractId, int schemaVersion);
    public Collection<NodeOutputContract<?>> all();
}
```

- [ ] **Step 1: 写测试**

```java
void requireReturnsRegisteredContract()
void requireFailsWithUnknownContractId()
void duplicateContractIdAndVersionFailsAtStartup()
void allReturnsRegistrationOrder()
```

- [ ] **Step 2: 运行失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-llm -am "-Dtest=NodeOutputContractRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 registry**

key 使用 `contractId + "@" + schemaVersion`；重复注册抛 `IllegalStateException`，错误必须带 key。

- [ ] **Step 4: 再跑测试**

Expected: PASS。

---

### Task A4: 实现 JsonSchemaSupport 与枚举渲染

**Boundary:** 只提供 JSON Schema 构建/校验和枚举渲染基础设施，不定义 ROUTE 业务规则。

**Why:** 用标准 schema 校验替代手写字段检查，并让枚举渲染只有一个来源。

**Inputs:** JSON Schema 和 JsonNode、Java enum values 集合。

**Outputs:** JsonSchemaSupport、schema hash、ContractViolation 转换、validator 依赖。

**Pass Criteria:** 合法样例通过，未知字段/枚举/缺失字段被拒绝且路径准确，定向测试通过。

**Files:**
- Create: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/JsonSchemaSupport.java`
- Create: `novelgen-llm/src/test/java/com/zzdzz/novelgen/llm/contract/JsonSchemaSupportTest.java`
- Modify: `novelgen-llm/pom.xml`

**Interfaces:**
- Produces:

```java
public final class JsonSchemaSupport {
    public static String bulletList(Collection<String> values);
    public static ArrayNode enumArray(Collection<String> values);
    public static String canonicalHash(JsonNode schema);
    public static List<ContractViolation> validate(JsonNode schema, JsonNode value);
}
```

- [ ] **Step 1: 写通用渲染测试**

断言 `enumArray(List.of("A","B"))` 输出 `["A","B"]`，`bulletList` 每一项独占一行。

还要覆盖 validator 支持的 JSON Schema 子集：

- `type`
- `required`
- `properties`
- `additionalProperties=false`
- `enum`
- `items`
- `minItems/maxItems`
- `minLength/maxLength`
- `minimum/maximum`
- `pattern`

- [ ] **Step 2: 写 schema 稳定测试**

同一枚举集合两次生成的 canonical hash 相同；顺序按 enum declaration order。

- [ ] **Step 3: 运行并确认失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-llm -am "-Dtest=JsonSchemaSupportTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现 support**

不引入第二份枚举字符串表。`bulletList` 只接收 `Enum::name` 的结果。
在 `novelgen-llm/pom.xml` 增加：

```xml
<dependency>
  <groupId>com.networknt</groupId>
  <artifactId>json-schema-validator</artifactId>
  <version>1.5.9</version>
</dependency>
```

使用该库做 JSON Schema 校验；`JsonSchemaSupport.validate` 把库返回的
validation message 转成 `ContractViolation`。
字段路径使用 JSON Pointer 风格，如 `$.beatNarratives[4].stateEffects[0].kind`。
遇到无法映射的 schema keyword 时启动测试失败，禁止静默忽略。

- [ ] **Step 5: 再跑测试**

Expected: PASS。

---

### Task A5: 实现 LlmJson.askStructured

**Boundary:** 只实现结构化的 LLM 调用执行器，携带 schema、领域校验、反馈和账本记录。

**Why:** 把语法修复、schema 校验和业务校验的职责边界固定在单一入口。

**Inputs:** NodeOutputContract、ChatRequest、tries 和底层 LlmPort。

**Outputs:** askStructured API、带路径的 retry feedback、CONTRACT_REJECTED 标记。

**Pass Criteria:** 三轮内合法输出返回；失败保留最后 violations；错误文案不再误用 seq/filled。

**Files:**
- Modify: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/LlmJson.java`
- Create: `novelgen-llm/src/test/java/com/zzdzz/novelgen/llm/LlmJsonStructuredContractTest.java`

**Interfaces:**
- Produces:

```java
public <T> T askStructured(NodeOutputContract<T> contract,
                           LlmPort.ChatRequest request,
                           int tries);
```

- [ ] **Step 1: 写测试 `parserViolationIsFedBackWithPath`**

Mock `LlmPort` 第一次返回非法 JSON，第二次返回合法 JSON；断言第二次 user message 含 path/expected/actual。

- [ ] **Step 2: 写测试 `duplicateStateKeyReportsDuplicateKeyNotSeqFilled`**

raw JSON 含两个 `"C1"`；断言 feedback 不含 `seq/filled`。

- [ ] **Step 3: 写测试 `allAttemptsFailReturnsLastViolations`**

断言异常 message 含最后一次 code/path。

- [ ] **Step 4: 实现**

执行顺序：

1. append `promptFragment`；
2. `read`；
3. `parser`；
4. `domainValidator`；
5. `markCall` 写 `CONTRACT_REJECTED|` 前缀和 violations；
6. retry。

语法错误也要转成 `ContractViolation`，但 code 分开：

- `SCHEMA_SYNTAX_INVALID`
- `SCHEMA_DUPLICATE_KEY`
- `SCHEMA_ENUM_INVALID`
- `SCHEMA_FIELD_INVALID`
- `DOMAIN_INVALID`

- [ ] **Step 5: 运行**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-llm -am "-Dtest=LlmJsonStructuredContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task A6: 定义 ROUTE v2 wire types

**Boundary:** 只定义 ROUTE v2 的 wire record、枚举和解析规则，不做章节分配。

**Why:** 从输入层移除模型对 beatRole、章号和稳定 ID 的决定权，消除重复键和枚举漂移源。

**Inputs:** 模型 ROUTE JSON、合同 cast/thread/clue keys。

**Outputs:** RouteNarrative、嵌套 effects、PlanContractSchemas 和解析错误。

**Pass Criteria:** 正样例通过；未知角色/线程/状态枚举、旧结构中的 beatRole 均被明确拒绝。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrative.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrativeContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContractSchemas.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContractConfiguration.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanContractSchemasTest.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/RouteNarrativeContractTest.java`

**Interfaces:**
- Produces:

```java
public record RouteNarrative(
        List<BeatNarrative> beatNarratives,
        List<SkippedBeat> skipped) {
    public record BeatNarrative(
            String beatKey,
            String goal,
            List<KnowledgeEffect> knowledgeEffects,
            List<StateEffect> stateEffects,
            List<ThreadEffect> threadEffects) {}

    public record KnowledgeEffect(List<String> characterKeys, String fact) {}
    public record StateEffect(String characterKey,
                              PlanContracts.StateChangeKind kind,
                              String detail) {}
    public record ThreadEffect(String threadKey,
                               PlanContracts.ThreadEffectKind effect,
                               String detail) {}
    public record SkippedBeat(String beatKey, String reason) {}
}

public final class PlanContractSchemas {
    public static final String ROUTE_NARRATIVE_ID = "derive.route_narrative";
    public static final int ROUTE_NARRATIVE_VERSION = 2;
    public static NodeOutputContract<RouteNarrative> routeNarrative();
}

@Configuration
public class PlanContractConfiguration {
    public PlanContractConfiguration(NodeOutputContractRegistry registry) {
        registry.register(PlanContractSchemas.routeNarrative());
    }
}
```

`ChapterSlotContract` 状态字段改为 `Map<String, List<String>>`。

在 `PlanContracts` 新增：

```java
enum StateChangeKind {
    LOCATION, PRESENT, ABSENT, DEAD, IDENTITY,
    POSSESSION_GAIN, POSSESSION_LOSS, RELATIONSHIP
}

enum ThreadEffectKind { OPEN, ECHO, PAYOFF, CLOSE }
```

- [ ] **Step 1: 写解析测试**

```java
void parsesBeatNarrativeWithArrayStateValues()
void rejectsUnknownCharacterInKnowledgeEffect()
void rejectsUnknownThreadInThreadEffect()
void rejectsUnknownBeatKey()
void routeV2WireTypeHasNoBeatRoleField()
void schemaEnumListMatchesAllJavaBeatRoles()
void unknownStateChangeKindIsSchemaEnumInvalid()
void unknownThreadEffectKindIsSchemaEnumInvalid()
void registryContainsRouteNarrativeV2()
```

- [ ] **Step 2: 运行失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RouteNarrativeContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 record 与 parser**

所有错误返回 `ContractViolation`，不要直接抛 P6 文案。

在 `PlanContractSchemas` 中从 `PlanContracts.BeatRole.values()`、`StoryLayer.values()`
等枚举生成 prompt fragment。禁止第三份手写枚举。

- [ ] **Step 4: 再跑测试**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RouteNarrativeContractTest,PlanContractSchemasTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task A7: 改写 ROUTE v2 prompt 与 PromptCatalog

**Boundary:** 只修改 ROUTE 提示词和 PromptCatalog 模板，不实现编译器。

**Why:** 把完整枚举白名单、schema version 和禁止结构字段的要求真正交给模型。

**Inputs:** PlanContractSchemas、Java 枚举定义、PromptCatalog。

**Outputs:** 带 token 的 ROUTE 模板和渲染后的 system/user prompt。

**Pass Criteria:** 所有枚举出现、所有 token 被替换、catalog 与渲染结果一致，定向测试通过。

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Modify: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/PromptCatalog.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/ContractPromptArityTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/RoutePromptContractTest.java`

**Interfaces:**
- Consumes: `PlanContractSchemas.routeNarrative()`
- Produces: ROUTE system/user prompt with schema fragment

`PromptCatalog` 只保存模板和占位符，不保存手写枚举清单。固定 token：

```text
{{BEAT_ROLES}}
{{STORY_LAYERS}}
{{APPEARANCE_MODES}}
{{STATE_CHANGE_KINDS}}
{{THREAD_EFFECT_KINDS}}
```

`PlanContractSchemas` 负责用 `JsonSchemaSupport.bulletList` 替换 token。

- [ ] **Step 1: 写 prompt 测试**

```java
void routePromptListsEveryBeatRoleName()
void routePromptForbidsBeatRoleInModelOutput()
void routePromptDefinesStateValueAsArray()
void renderedRoutePromptMatchesCatalogTemplateAfterTokenResolution()
void routePromptHasNoUnresolvedTemplateToken()
```

- [ ] **Step 2: 实现 prompt**

ROUTE v2 只要求模型输出：

- `beatNarratives.beatKey`
- `beatNarratives.goal`
- knowledge/state/thread effects
- `skipped.beatKey/reason`

禁止出现：

- `beatRole`
- `chapterNo`
- `seq`
- 自造 thread/clue/person key

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RoutePromptContractTest,ContractPromptArityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task A8: 增加 LLM 调用“有效状态”投影

**Boundary:** 只增加调用账本的有效状态投影，不修改历史 llm_call_log 表。

**Why:** 区分 HTTP 成功和内容合规，修复 UI 与 run 级状态被成功标签掩盖的问题。

**Inputs:** 原始 status 和 error_msg。

**Outputs:** ACCEPTED/CONTENT_REJECTED/CALL_FAILED/INTERRUPTED 及 VO 字段。

**Pass Criteria:** 四类状态映射测试通过，旧 status 字段保留兼容。

**Files:**
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/enums/LlmCallEffectiveStatusEnum.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQueryService.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/LlmTrailVO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/LlmCallDetailVO.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/LlmEffectiveStatusTest.java`

**Interfaces:**
- Produces:

```java
enum LlmCallEffectiveStatusEnum {
    ACCEPTED, CONTENT_REJECTED, CALL_FAILED, INTERRUPTED
}

static LlmCallEffectiveStatusEnum effectiveStatus(String transportStatus, String error);
```

- [ ] **Step 1: 写四分支测试**

```java
void okWithoutErrorIsAccepted()
void okWithContractRejectedIsContentRejected()
void errorContainingInterruptedIsInterrupted()
void otherErrorIsCallFailed()
```

- [ ] **Step 2: 运行失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=LlmEffectiveStatusTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现并接入 VO**

`LlmTrailVO` 新增 `effectiveStatus` 和 `effectiveStatusLabel`；保留原 `status` 供兼容。

- [ ] **Step 4: 再跑测试**

Expected: PASS。

---

### Task A9: 修改前端调用账本标签

**Boundary:** 只修改前端展示，不改变后端判定。

**Why:** 让用户一眼看出“模型返回成功但内容不合规”，不再误判为模型稳定。

**Inputs:** effectiveStatus 和错误文本。

**Outputs:** 四种颜色/文案标签。

**Pass Criteria:** 前端构建通过，源码不再用 status=ok 直接表示成功。

**Files:**
- Modify: `web/src/views/derive/DeriveRunDetailView.vue`
- Modify: `web/src/views/derive/deriveLabels.js`

**Interfaces:**
- Consumes: `effectiveStatus`
- Produces: 四种状态标签

- [ ] **Step 1: 替换模板**

禁止 `row.status === 'ok' ? '成功' : '失败'`。

映射：

```text
ACCEPTED -> green 成功
CONTENT_REJECTED -> orange 内容未通过
CALL_FAILED -> red 调用失败
INTERRUPTED -> gray 人工中断
```

- [ ] **Step 2: 构建前端**

```powershell
cd web
npm run build
```

Expected: 构建成功。

---

### Task A10: 完成真实 fixture 回放

**Boundary:** 只增加历史 fixture 的回放验收，不增加新业务能力。

**Why:** 证明新的协议不会重新接受已知坏输出，也不会错误拒绝已知好输出。

**Inputs:** Task A1-A9 的契约、parser、fixture。

**Outputs:** run44/58/60 的分类回归测试。

**Pass Criteria:** 指定坏输出得到指定 error code/path，合法输出通过，定向测试全绿。

**Files:**
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/HistoricalRouteReplayTest.java`

**Interfaces:**
- Consumes: Tasks A1–A9
- Produces: 历史失败分类证据

- [ ] **Step 1: 写 run44/60 回放测试**

断言：

```java
run44C1DuplicateFailsWithSchemaDuplicateKey()
run60ObstacleFailsWithSchemaEnumInvalid()
run60MidpointFailsWithSchemaEnumInvalid()
validRoutePasses()
```

- [ ] **Step 2: 写 run58 Head C 回归**

断言 clueKey 缺失是 `DOMAIN_INVALID`，不是语法错误。

- [ ] **Step 3: 跑定向测试**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=HistoricalRouteReplayTest,RouteNarrativeContractTest,LlmJsonStructuredContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

## Completion Check

- [ ] `LlmJson` 语法层和节点契约层职责分离。
- [ ] `BeatRole` 白名单来自唯一 Java 定义。
- [ ] ROUTE v2 不再允许模型输出结构字段。
- [ ] 状态值支持一个角色多个事实且无重复 JSON 键。
- [ ] run44/58/60 的真实失败输出全部可离线回放。
- [ ] UI 不再把内容拒绝显示为成功。
- [ ] 全量测试通过。
