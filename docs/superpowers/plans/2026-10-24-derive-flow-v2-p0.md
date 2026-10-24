# DeriveFlow v2 P0 自动修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先落地不迁移数据库的确定性结构修复，解决 run 39/40 暴露的 forced-skip、synthetic 字段、线程兑现与知识来源问题。

**Architecture:** 继续使用现有 `derive_gen` JSONB 合同；在 `DeriveBeatRouteService` 做路由合同归一和结构修复；在 `DerivePlanService.repairPersistedKnowledge` 做知识来源归一；P6 前继续复用 `DerivePlanValidationService` 做硬校验。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL JSONB、JUnit 5。

**Spec:** `docs/design/derive-flow-v2-repair-plan.md`

## Global Constraints

- 不修改主站 `novel_gen`。
- 不删除或破坏 `derive_gen` 存量数据。
- 不静默修改故事真相；自动修复只允许结构和可唯一定位的知识传播。
- 不执行 commit/push；不部署线上。
- 遵守 AGENTS.md、`docs/code-standards.md`、`docs/STATUS.md`。

## Review Focus

1. forced-skip seq 是否会被模型 assignments 回显并触发重复；
2. synthetic 拍是否保留 `requiresKnowledge/writesKnowledge/requiresState/changesState`；
3. requiredThreads 是否有最终兑现拍；
4. `requiresKnowledge` 是否能在更早拍中找到 writer；
5. 修复后是否改变既有章节预算、拍数覆盖和 P6 语义校验。

## File Structure

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanService.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePlanKnowledgeRepairTest.java`
- Modify: `docs/STATUS.md`

## Task 1: Thread payoff repair

**Files:** `DeriveBeatRouteService.java`, `DeriveBeatRouteServiceTest.java`

**Interfaces:**
- `ensureRequiredThreadPayoffs(List<ChapterSlotContract>, List<BeatAssignment>, List<SyntheticAssignment>, RouteContext) -> List<SyntheticAssignment>`

**Steps:**
- [ ] Step 1: Add a failing test where `requiredThreads` contains `GM-CLUE-01` but no payoff beat references it.
- [ ] Step 2: Implement deterministic payoff append to an existing final/payoff beat, or create a final closure synthetic beat.
- [ ] Step 3: Run `mvn -q test -pl novelgen-service -am "-Dtest=DeriveBeatRouteServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`.
- [ ] Step 4: Commit as `fix(derive): auto close required story threads`.

## Task 2: Knowledge writer normalization

**Files:** `DerivePlanService.java`, `DerivePlanKnowledgeRepairTest.java`

**Interfaces:**
- `factCore(String) -> String`
- `factMatches(String writerFact, String requiredFact) -> boolean`
- `repairPersistedKnowledge(long runId)` remains private but uses the helpers.

**Steps:**
- [ ] Step 1: Add failing tests for normalized report facts, e.g. `C2完成的详细报告内容` matching `C2在公共餐饮处完成详细报告，证据成型`.
- [ ] Step 2: Implement fact normalization and earlier-writer matching.
- [ ] Step 3: When a matching earlier writer exists, append the exact required fact to that writer’s `writesKnowledge` and add missing focus characters.
- [ ] Step 4: Run targeted knowledge repair tests.
- [ ] Step 5: Commit as `fix(derive): normalize persisted knowledge writers`.

## Task 3: Run 39/40 regression fixtures

**Files:** `DeriveBeatRouteServiceTest.java`, `DerivePlanKnowledgeRepairTest.java`

**Steps:**
- [ ] Step 1: Add regression assertions for run39 forced-skip echo and run40 report knowledge mismatch.
- [ ] Step 2: Run full `mvn -q test`.
- [ ] Step 3: Update `docs/STATUS.md` with evidence.

## Task 4: P0 verification

**Steps:**
- [ ] Step 1: `mvn -q compile`.
- [ ] Step 2: `mvn -q test`.
- [ ] Step 3: Build derive jar with `mvn -q -pl novelgen-derive-app -am package -DskipTests`.
- [ ] Step 4: Do not deploy unless separately authorized.