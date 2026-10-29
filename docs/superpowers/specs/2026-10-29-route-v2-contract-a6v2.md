# ROUTE v2 Wire Contract A6V2 Execution Spec

> 这是当前唯一有效的 A6 执行契约。原 `2026-10-28-derive-output-contract-hardening.md` 中的 A6 任务已废弃，不得再按原文验收。

## 目标

建立独立的 ROUTE v2 wire contract 基座：

- 模型只输出叙事效果；
- 模型不输出 `beatRole`、`chapterNo`、`seq`、`storyLayer` 或稳定 ID；
- schema、解析器、领域校验和 registry 使用同一套 `RouteNarrative`；
- 未知角色、线程、beat key、枚举字段和旧结构字段得到带字段路径的 `ContractViolation`；
- 本任务不迁移 legacy `ChapterSlotContract`。

## Boundary

只实现：

- `RouteNarrative`
- `RouteNarrativeContract`
- `PlanContractSchemas`
- `PlanContractConfiguration`
- `PlanContracts.StateChangeKind`
- `PlanContracts.ThreadEffectKind`
- 对应测试

只读取 `PlanContracts.BeatRole` 等现有枚举，不修改 `DeriveBeatRouteService`、`ChapterSlotContract`、数据库、正文、P6 或编排器。

## Inputs

- A2 的 `NodeOutputContract`、`ContractViolation`
- A4 的 `JsonSchemaSupport`
- `PlanContracts` 现有枚举
- `derive-replay/route-valid.json`
- `derive-replay/run60-route-obstacle.json`
- `derive-replay/run60-route-c1-duplicate.json`
- `derive-replay/run60-route-midpoint.json`

## Outputs

```java
public final class PlanContractSchemas {
    public static NodeOutputContract<RouteNarrative> routeNarrative();
}

public final class RouteNarrativeContract {
    public record RouteContractKeys(
            Set<String> castKeys,
            Set<String> threadKeys,
            Set<String> beatKeys) {}

    public record ParseResult(
            RouteNarrative value,
            List<ContractViolation> violations) {
        public boolean valid();
    }

    public static RouteNarrative parseStructure(JsonNode root);
    public static List<ContractViolation> validate(
            RouteNarrative narrative, RouteContractKeys keys);
    public static List<ContractViolation> validateContextFree(RouteNarrative narrative);
    public static ParseResult parse(JsonNode root, RouteContractKeys keys);
}
```

`RouteNarrativeContract.parse` 是唯一需要包装为 `ContractViolation` 的公共入口。`parseStructure` 可以是低层结构解析，但它不得直接用于生产调用链。

## Pass Criteria

- `RouteNarrative` 合法正样例可解析；
- 未知 `characterKeys` 被拒绝；
- 未知 `threadKey` 被拒绝；
- 未知 `beatKey` 被拒绝；
- `beatRole`、`chapterNo`、`seq` 等旧字段被 schema 拒绝；
- `StateChangeKind` 和 `ThreadEffectKind` schema 的 enum 值来自 `PlanContracts.values()`；
- registry 中存在 `derive.route_narrative@2`；
- 所有公开 parse 入口失败返回 `ContractViolation`；
- 测试首次失败、实现后通过；
- 未修改 legacy ROUTE parser、`ChapterSlotContract`、数据库或生产 LLM 调用。

## Files

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Create/Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrative.java`
- Create/Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrativeContract.java`
- Create/Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContractSchemas.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContractConfiguration.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/RouteNarrativeContractTest.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanContractSchemasTest.java`
- Create: `var/task-a6v2/test-before.log`

## Required Tests

```java
void parsesHistoricalRouteFixture()
void rejectsUnknownCharacter()
void rejectsUnknownThread()
void rejectsUnknownBeatKey()
void rejectsLegacyBeatRoleField()
void rejectsUnknownStateChangeKind()
void rejectsUnknownThreadEffectKind()
void schemaUsesAllStateChangeKinds()
void schemaUsesAllThreadEffectKinds()
void schemaRejectsAdditionalProperties()
void registryContainsRouteNarrativeV2()
```

## Test Evidence

在实现前运行：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=RouteNarrativeContractTest,PlanContractSchemasTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

把失败输出保存到：

```text
var/task-a6v2/test-before.log
```

实现后运行同一命令，必须为 PASS。

## 禁止事项

- 不修改 `ChapterSlotContract`；
- 不修改 `DeriveBeatRouteService`；
- 不修改 legacy ROUTE prompt；
- 不实现 B8 的 narrative expander；
- 不新增迁移；
- 不 commit、push 或部署。

## 后续任务

完成 A6V2 并通过 Reviewer 后，进入 `A6B`。A6B 负责：

- 迁移 `ChapterSlotContract.requiredStateBefore/After`；
- 同步 legacy ROUTE parser；
- 同步 prompt 和调用方；
- 迁移完成后才能继续原 A7/A8/A9/A10。
