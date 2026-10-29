# ROUTE v2 Legacy Contract Migration A6B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Only run after A6V2 is APPROVE。

**Goal:** 迁移 legacy ROUTE 状态字段与枚举 prompt，使旧 ROUTE parser 与 ROUTE v2 的状态/枚举规则一致。

**Architecture:** 由 Java 统一维护 `PlanContracts` 枚举和状态值类型；`DeriveBeatRouteService`、prompt、schema 和测试共同消费同一份定义。旧 ROUTE parser 只做兼容解析，不能新增一套枚举表。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Jackson、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-10-29-route-v2-contract-a6v2.md`

## Global Constraints

- A6V2 已 APPROVE 后才能开始；
- 不修改数据库、迁移、正文 writer、P6、orchestrator；
- 不允许在 legacy parser 中维护第二份枚举；
- 所有 JSONB 不涉及本任务；
- 未获用户当轮允许，禁止 commit/push/deploy。

## Files

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Modify: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/PromptCatalog.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/ContractPromptArityTest.java`
- Create: `var/task-a6b/test-before.log`

## Interfaces

Produces:

```java
Map<String, List<String>> requiredStateBefore;
Map<String, List<String>> requiredStateAfter;
```

Legacy parser must:

- reject duplicate character keys as `SCHEMA_DUPLICATE_KEY`;
- reject unknown state/thread/beat keys with JSON Pointer paths;
- accept the canonical `StateChangeKind` and `ThreadEffectKind` values;
- reject `beatRole` in new ROUTE v2 output;
- keep legacy prompt/parser behavior compatible for old data only through explicit adapter code.

## Required Tests

```java
void routePromptListsAllBeatRoles()
void routePromptListsAllStoryLayers()
void routePromptListsAllAppearanceModes()
void legacyStateMapRejectsDuplicateCharacterKeys()
void legacyParserAcceptsCanonicalStateKinds()
void legacyParserRejectsUnknownStateKind()
void legacyParserRejectsUnknownThreadEffectKind()
void legacyParserRejectsUnknownBeatKey()
```

## Verification

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DeriveBeatRouteServiceTest,ContractPromptArityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Pass Criteria: 全部测试通过；旧 ROUTE parser 不新增第二份枚举；legacy 与 v2 的状态类型一致。
