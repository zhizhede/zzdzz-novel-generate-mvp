# ROUTE v2 Wire Contract Task Redesign (A6 v2)

日期：2026-10-29  
范围：只重设计 Task A6，不修改现有 A6 原文，不实现代码。

## 1. 为什么需要 v2

原 A6 有四个执行阻塞：

1. **文件边界不可编译**：A6 要求把 `ChapterSlotContract.requiredStateBefore/After` 改成 `Map<String, List<String>>`，但现有调用方 `DeriveBeatRouteService` 不在允许文件内，直接改 record 会导致编译失败。
2. **校验边界冲突**：A6 要求 `rejectsUnknownBeatKey()`，但输入只提供 cast/thread/clue keys，没有合法 beat key 清单；合法 beat keys 实际属于结构计划/B8 的上下文。
3. **接口不完整**：`RouteNarrativeContract` 只给了类名，没有给出 parse/validate API、上下文类型或错误返回方式。
4. **验收命令假绿**：`-Dsurefire.failIfNoSpecifiedTests=false` 在测试类不存在时仍返回 0，无法证明测试真的跑过。

## 2. v2 目标

A6 v2 只交付 ROUTE v2 的独立 wire contract 基座：

- 定义 `RouteNarrative` 及其嵌套 records；
- 定义 `StateChangeKind`、`ThreadEffectKind`；
- 定义 ROUTE v2 JSON Schema；
- 提供结构解析和上下文相关的领域校验 API；
- 注册 `derive.route_narrative@2`；
- 用正样例和负样例证明：
  - 正常 wire JSON 可解析；
  - 未知角色、线程、beat key 被拒绝；
  - `beatRole/chapterNo/seq` 等旧结构字段被 schema 拒绝；
  - 未知 `StateChangeKind/ThreadEffectKind` 被 schema 拒绝。

## 3. v2 非目标

A6 v2 不做：

- 不修改 `ChapterSlotContract`；
- 不修改 `DeriveBeatRouteService` 或任何 legacy ROUTE parser；
- 不做章节分配；
- 不实现 `PlanNarrativeExpander`；
- 不接入具体 LLM 调用流程；
- 不修改 `StoryPlanCompiler`；
- 不新增数据库迁移；
- 不修改前端。

`ChapterSlotContract` 的 `Map<String, List<String>>` 迁移放到后续独立任务，必须同时带上调用方和测试文件，不能塞进 A6。

## 4. v2 组件设计

### 4.1 RouteNarrative

保持原接口：

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

    public record StateEffect(
            String characterKey,
            PlanContracts.StateChangeKind kind,
            String detail) {}

    public record ThreadEffect(
            String threadKey,
            PlanContracts.ThreadEffectKind effect,
            String detail) {}

    public record SkippedBeat(String beatKey, String reason) {}
}
```

### 4.2 RouteNarrativeContract API

`RouteNarrativeContract` 是纯 Java 校验器，不依赖 Spring：

```java
public final class RouteNarrativeContract {

    public record RouteContractKeys(
            Set<String> castKeys,
            Set<String> threadKeys,
            Set<String> beatKeys) {}

    public record ParseResult(
            RouteNarrative value,
            List<ContractViolation> violations) {
        public boolean valid() {
            return value != null && violations.isEmpty();
        }
    }

    public static RouteNarrative parseStructure(JsonNode root);

    public static List<ContractViolation> validate(
            RouteNarrative narrative,
            RouteContractKeys keys);

    public static ParseResult parse(
            JsonNode root,
            RouteContractKeys keys);
}
```

职责：

- `parseStructure`：只负责 wire JSON → `RouteNarrative`，不做合同上下文校验；
- `validate`：检查角色、线程、beat key、重复 beat、空白字段等业务规则；
- `parse`：组合结构解析与上下文校验，返回 value + violations；
- 所有失败使用 `ContractViolation`，不直接抛 P6 文案。

错误码至少包括：

- `SCHEMA_FIELD_INVALID`
- `SCHEMA_ENUM_INVALID`
- `DOMAIN_UNKNOWN_CHARACTER`
- `DOMAIN_UNKNOWN_THREAD`
- `DOMAIN_UNKNOWN_BEAT_KEY`
- `DOMAIN_DUPLICATE_BEAT_KEY`
- `DOMAIN_BLANK_FIELD`

路径使用 JSON Pointer 风格，例如：

```text
$.beatNarratives[0].knowledgeEffects[0].characterKeys[1]
$.beatNarratives[2].threadEffects[0].threadKey
$.beatNarratives[4].beatKey
```

### 4.3 PlanContractSchemas

```java
public final class PlanContractSchemas {
    public static final String ROUTE_NARRATIVE_ID = "derive.route_narrative";
    public static final int ROUTE_NARRATIVE_VERSION = 2;

    public static NodeOutputContract<RouteNarrative> routeNarrative();
}
```

`routeNarrative()` 只负责：

- schema view；
- prompt fragment；
- 结构 parser；
- 上下文无关的重复/空白校验。

schema 必须：

- `additionalProperties=false`；
- 禁止 `beatRole/chapterNo/seq/storyLayer` 等旧结构字段；
- `StateChangeKind` enum 从 `PlanContracts.StateChangeKind.values()` 生成；
- `ThreadEffectKind` enum 从 `PlanContracts.ThreadEffectKind.values()` 生成；
- 不维护第二份手写枚举。

上下文相关 key 校验由 `RouteNarrativeContract.validate` 承担，不硬塞进静态 `NodeOutputContract`，因为静态 contract 拿不到当前 run 的合法 keys。

### 4.4 PlanContractConfiguration

```java
@Configuration
public class PlanContractConfiguration {
    public PlanContractConfiguration(NodeOutputContractRegistry registry) {
        registry.register(PlanContractSchemas.routeNarrative());
    }
}
```

只注册 ROUTE v2，不注册后续任务的契约。

## 5. v2 测试契约

`RouteNarrativeContractTest` 至少覆盖：

```java
void parsesBeatNarrativeWithArrayStateValues()
void rejectsUnknownCharacterInKnowledgeEffect()
void rejectsUnknownThreadInThreadEffect()
void rejectsUnknownBeatKey()
void rejectsDuplicateBeatKey()
void routeV2WireTypeHasNoBeatRoleField()
void unknownStateChangeKindIsSchemaEnumInvalid()
void unknownThreadEffectKindIsSchemaEnumInvalid()
void registryContainsRouteNarrativeV2()
```

`PlanContractSchemasTest` 至少覆盖：

```java
void schemaUsesAllStateChangeKinds()
void schemaUsesAllThreadEffectKinds()
void schemaRejectsLegacyBeatRoleField()
void routeNarrativeSchemaHasAdditionalPropertiesFalse()
```

正样例使用：

`novelgen-service/src/test/resources/derive-replay/route-valid.json`

该文件是 A1 已提供的 SYNTHETIC fixture，不修改其来源声明。

## 6. v2 验收命令

实现前必须真正失败：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RouteNarrativeContractTest" "-Dsurefire.failIfNoSpecifiedTests=true"
```

实现后必须真正执行并产生报告：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RouteNarrativeContractTest,PlanContractSchemasTest" "-Dsurefire.failIfNoSpecifiedTests=true"
```

额外验收：

```powershell
$reportDir = "novelgen-service/target/surefire-reports"
$required = @(
  "$reportDir/TEST-com.zzdzz.novelgen.derive.plan.RouteNarrativeContractTest.xml",
  "$reportDir/TEST-com.zzdzz.novelgen.derive.plan.PlanContractSchemasTest.xml"
)
foreach ($path in $required) {
  if (-not (Test-Path -LiteralPath $path)) { throw "missing surefire report: $path" }
}
```

## 7. v2 后续任务边界

- A6 v2：wire type + schema + 独立 validator；
- B8：把 `RouteNarrative` 合并进结构计划，并用 `CompiledStoryPlan` 的 beat key 清单做最终 unknown/missing/skipped 校验；
- 后续单独任务：迁移 `ChapterSlotContract` 状态字段到 `Map<String, List<String>>`，必须同时修改 legacy route parser 及其测试。

## 8. v2 完成标准

- 七个目标 Java 文件存在且只在允许范围内；
- `PlanContracts` 只新增两个 enum，不改 `ChapterSlotContract`；
- 正样例通过；
- 四类负样例拒绝；
- 两个测试类真正执行并通过；
- surefire report 存在；
- 无 SQL、无 mapper、无数据库迁移、无模块依赖反转；
- 未 commit/push/deploy。
