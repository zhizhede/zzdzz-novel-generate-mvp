# DeriveFlow P6 内容合同修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 DeriveFlow 在保留 P6 内容质量标准的前提下，通过 budget、function_lock 及相关内容审查，并跑到完整终态。

**Architecture:** 保留 Java 对 P6 的终态裁决；把重复章纲、重复拍、拍负载、persona/角色映射问题拆成四个局部修复层：采样/路由语义去重 → 具体稿语义校验 → 角色合同对齐 → P6 指令闭环。

**Tech Stack:** Java 21、Spring Boot、Jackson、PostgreSQL、JUnit/Mockito。

**Spec:** `docs/STATUS.md` 的「run43 线上排查结论」「DeriveFlow P0/P1 修复与 run44 实测」；设计依据 `docs/design/衍生故事规划与节拍路由设计.md`。

## Global Constraints

- 不降低 `single_rule`、`closure`、`motive_chain`、`motif_dedup` 的通过标准。
- 不重新引入旧 O-F01 双轨主线；TruthContract 仍是唯一真相源。
- 不清空全书重规划；修复必须局部、可重试、可回放。
- 章号、拍 seq、线程 key、角色 key 继续由系统归一。
- 所有确定性修复写 `llm_call_log.error_msg` 或 `derive_repair_log`。
- 不 commit/push，除非用户明确要求；部署需用户明确授权。

## Review Focus

- 两个不同 seq 表达同一剧情时，系统必须识别为语义重复，而不是只看 seq 是否唯一。
- synthetic 拍不能用“补齐结构功能”这种空目标诱导模型复述已有事件。
- 相邻章 outline 归一化后相同，必须在 P6 前修复。
- 单章拍数超过预算时，只能搬移非 required-role 拍。
- persona 明确禁止某行为时，章纲不得继续安排该行为。
- 角色计划的 `arcBefore/arcAfter` 必须与人物表 role/persona 对齐。
- P6 `directive` 为空时必须自动可执行。

## Task 1: synthetic 拍目标与标签去空泛化

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveSyntheticGoalTest.java`

**Interfaces:**
- Consumes: `ChapterSlotContract.mainMysteryAdvance/localCaseGoal`、`BeatInstanceDO.route_contract`
- Produces: `static String syntheticGoal(ChapterSlotContract chapter, PlanContracts.BeatRole role)`

**Behavior:**
- `synthesizeMissingRoles` 的 goal 改为：
  `推进第X章既定目标：<mainMysteryAdvance 或 localCaseGoal>；必须产生新增信息，禁止复述已有拍`
- `buildLabelBySeq` 对 `source=SYNTHETIC` 返回 `route_contract.goal`，不再返回空标签。
- ROUTE 提示词增加：syntheticSlots 必须推进本章新信息，不得复述已有 seq。

**Steps:**
- [ ] 写测试 `syntheticGoalContainsChapterTargetAndNoRepeatInstruction`
- [ ] 写测试 `syntheticLabelIsExposedToDraftWindow`
- [ ] 修改 `synthesizeMissingRoles`
- [ ] 修改 `buildLabelBySeq`
- [ ] 修改 ROUTE 提示词
- [ ] 运行 `mvn -q test -pl novelgen-service -am "-Dtest=DeriveSyntheticGoalTest,DeriveBeatRouteServiceTest"`

## Task 2: 具体稿语义重复检测与重写

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveDraftSemanticDedupTest.java`

**Interfaces:**
- Consumes: `List<BeatInstanceDO>`、`List<ChapterDO>`、`NovelDO`
- Produces:
  - `static double semanticSimilarity(String left, String right)`
  - `static void normalizeDraftContent(List<ChapterDO> chapters, List<BeatInstanceDO> beats)`

**Behavior:**
- 归一化：去标点、空白、英文大小写，用中文/英文二元字符组 Jaccard。
- `filled_text` 相似度 ≥ `0.72` 且位于相邻/同章窗时，后一个拍标记为语义重复。
- 重复拍处理：
  1. 优先标 `SKIPPED`
  2. 若该章因此没有有效拍，用同章其他拍重写 outline
  3. 若全章只有一个重复拍，则保留该拍，但 outline 必须从其他章节信息生成，不能复述前章
- 相邻章 `draft_outline` 归一化后相同：
  - 用后章 `filled_text` 生成新的推进式 outline
  - 记录 `derive_repair_log`，`stage=DRAFT`、`repairType=DUPLICATE_OUTLINE`
- 终局对账前调用 `normalizeDraftContent`。

**Steps:**
- [ ] 写测试 `duplicateFilledTextsAreDetectedBySimilarity`
- [ ] 写测试 `duplicateAdjacentOutlinesAreRewrittenFromBeats`
- [ ] 写测试 `requiredRoleBeatIsNotSkippedEvenWhenSimilar`
- [ ] 实现 `semanticSimilarity`
- [ ] 实现 `normalizeDraftContent`
- [ ] 接入 `DeriveDraftService.draft()` 终局对账前
- [ ] 运行 `mvn -q test -pl novelgen-service -am "-Dtest=DeriveDraftSemanticDedupTest"`

## Task 3: 拍负载与章容量归一

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatLoadBalanceTest.java`

**Interfaces:**
- Consumes: `List<BeatInstanceDO>`、`ChapterDO.budget`、required roles
- Produces: `static void rebalanceBeatLoad(List<ChapterDO> chapters, List<BeatInstanceDO> beats)`

**Behavior:**
- 单章有效拍上限：
  `max(2, ceil(totalBeats / chapterCount * 1.5))`
- 只搬移：
  - `MAIN_ADVANCE`
  - `CLUE_ECHO`
  - `CLUE_PLANT`
  - 非 required-role 的 synthetic 拍
- 不搬移：
  - `HOOK`
  - `HIGHLIGHT`
  - `CHARACTER_CHOICE`
  - `COST`
  - `LOCAL_CLOSURE`
  - `FINAL_CLOSURE`
  - 任何被章槽 `requiredBeatRoles` 要求的拍
- 搬移后必须重新检查每章至少 1 拍、章号在卷范围内、seq 不变。
- `route_contract.chapterNo` 与 `chapters.chapter_no` 保持一致。

**Steps:**
- [ ] 写测试 `overloadedChapterBeatsAreRebalanced`
- [ ] 写测试 `requiredClosureBeatsAreNeverMoved`
- [ ] 写测试 `rebalanceKeepsSeqAndChapterConsistency`
- [ ] 实现 `rebalanceBeatLoad`
- [ ] 接入路由持久化前与具体稿终局对账前
- [ ] 运行 `mvn -q test -pl novelgen-service -am "-Dtest=DeriveBeatLoadBalanceTest"`

## Task 4: persona/角色合同对齐

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/PlanContracts.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePersonaAlignmentTest.java`

**Interfaces:**
- Consumes: `story_assets.cast`、`story_assets.brief`、`CharacterPlanDO`
- Produces:
  - `CharacterContract.castRole`
  - `static List<PlanContracts.PlanIssue> validatePersonaAlignment(List<CharacterPlanDO> characters, JsonNode cast)`

**Behavior:**
- `CharacterContract` 增加 `castRole` 字段，保存人物表原始 role。
- CHARACTERS 提示词输入增加 cast role/persona/brief。
- 要求：
  - `arcBefore/arcAfter` 必须与 `castRole` 一致
  - `functionalConflict/highlightChoice` 不得违反 persona conduct
  - C1 必须是 DETECTIVE 主视角；OPPOSITION 不得与 DETECTIVE 共用同一姓名
- HEAD_B 提示词增加：
  - persona conduct 不得写与 TruthContract 主角动作互斥的绝对禁令
  - 若 TruthContract 要求主角进入现场，persona 必须允许受控进入或有明确授权
- 根因回炉时保留 `story_assets.cast/protagonist`，只清空需要重做的 `brief/truth`，让角色合同能读取已有 role。
- 新增错误码：
  - `PERSONA_ROLE_MISMATCH`
  - `CHARACTER_FUNCTION_NAME_COLLISION`
  - `repairTarget=DRAFT`

**Steps:**
- [ ] 写测试 `castRoleIsPersistedInCharacterContract`
- [ ] 写测试 `personaProtagonistActionConflictIsReported`
- [ ] 写测试 `detectiveAndOppositionCannotShareName`
- [ ] 修改 `CharacterContract`
- [ ] 修改 CHARACTERS/HEAD_B 提示词
- [ ] 实现 `validatePersonaAlignment`
- [ ] 运行 `mvn -q test -pl novelgen-service -am "-Dtest=DerivePersonaAlignmentTest,DeriveStoryPlanTruthContractTest"`

## Task 5: P6 directive 闭环

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateDirectiveTest.java`

**Interfaces:**
- Consumes: `GateReview`、`PlanValidationVO`
- Produces: `static String buildDirective(List<String> issues, String repairTarget)`

**Behavior:**
- `review.directive` 为空且存在 REWORK 目标时，自动生成：
  `按 TruthContract 修复以下问题：<issues>；repairTarget=<target>`
- directive 必须写入 `step_status.steps.GATE.directive`
- `gateResetForRootRebuild` 继续保留轮次，不回零
- `decideGate` 轮次熔断逻辑保持不变

**Steps:**
- [ ] 写测试 `blankDirectiveIsGeneratedFromIssues`
- [ ] 写测试 `directiveIsPersistedAcrossGateReset`
- [ ] 实现 `buildDirective`
- [ ] 接入 `DeriveGateService.gate()`
- [ ] 运行 `mvn -q test -pl novelgen-service -am "-Dtest=DeriveGateDirectiveTest,DeriveGateServiceTest"`

## Task 6: 线上回归与终态验收

**Files:**
- Modify: `docs/STATUS.md`
- Test: 宝塔 `run44`

**Acceptance Criteria:**
- 本地全量 `mvn -q test` 通过
- derive jar 部署成功，SHA256 记录
- run44 `clear-planning + auto?direct=false`
- P6 不再出现：
  - 相邻章 outline 重复
  - synthetic 拍复述已有事件
  - 单章拍数严重超载
  - persona/角色合同互斥
  - 空 directive 导致无效回炉
- P6 `single_rule/closure/motive_chain/motif_dedup` 必须 PASS
- `budget/function_lock` 必须 PASS 或仅 ADVISORY
- run44 最终达到 `PASSED`；若未达到，保留完整 issues 并回到对应任务，不得用 `direct=true` 绕过

**Steps:**
- [ ] 全量测试
- [ ] 打包
- [ ] 部署宝塔
- [ ] 重置并启动 run44
- [ ] 记录 P6 每轮 issues/repairTarget
- [ ] 达到 PASSED 后追加 STATUS
- [ ] 不 commit/push，除非用户明确授权