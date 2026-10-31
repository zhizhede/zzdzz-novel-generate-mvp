# 随机衍生通用故事合同 Implementation Plan V2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前已落地的通用故事合同骨架上，补齐义务证据模型、完整 StoryKernel 装配、⑥b 窗口语义校验、P6/QC 真实审计和非悬疑插件，避免“新增类和单测通过”被误当作业务完成。

**Architecture:** 保留现有 `SAMPLING → PLANNING → DRAFTING → P6 → ⑦ → ⑧` 主链；将当前 `StoryObligation` 从“字段合同”升级为“合同 + 证据 + 状态”；P6/QC 不再用空 StoryKernel 或字符串包含判断义务；`MysteryProfile` 与新增 `RomanceProfile` 通过同一 `GenreProfile` 接口接入。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Flyway、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design-v2.md`

## Global Constraints

- 先读 V2 设计文档、旧施工计划、`最高约束.md`、`AGENTS.md`、`docs/code-standards.md` 和当前相关 Java/XML/SQL。
- 不删除旧 `StoryContract`、旧 ROUTE parser、P6、QC 或旧 run 读路径。
- 通用核心不得出现 `culprit`、`motive`、`method`、`revealChapter`。
- 义务证据不能用 `text.contains(targetKey)` 作为兑现判断。
- P6/QC 构造的 `StoryKernel` 必须包含真实 payload、实体、线程、事件和证据。
- 未经用户明确授权，禁止 commit、push、部署。
- 每个任务先写失败测试，再实现，再运行相关测试；最后运行全量测试和打包。
- Flyway 只能新增迁移，不得修改历史迁移。
- 数据库 JSONB 必须走 XML 显式 `::jsonb`。
- 旧 run 无 V143/V144/V145 数据时必须仍可读取。

## Review Focus

以下失败模式必须由任务测试覆盖：

1. **义务 payload 丢失**：P6/QC 读取 `derive_story_obligation` 后必须恢复 payload，不能使用 `Map.of()`。
2. **字符串误杀**：QC 不能因为正文不包含 `GM-CLUE-01` 就判失败；必须使用证据索引。
3. **空 StoryKernel**：P6/QC 不能把 `entities/events/worldStates` 全部置空后审计。
4. **窗口假阴性**：提前 payoff、重复 payoff、缺 setup、状态冲突、知识来源缺失必须在 ⑥b 后立刻失败。
5. **旧 run 回归**：无 `genre`、无 obligation 行、无 V145 证据表的旧 run 必须仍能读取和运行旧链。

---

## Task 1: 扩展义务证据模型

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryObligationEvidence.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryEvidenceIndex.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryEvidenceIndexTest.java`

**Interfaces:**

- Consumes: `StoryObligation`、`StoryEvent`、`StoryThread`。
- Produces:

```text
StoryObligationEvidence(
    String obligationKey,
    int chapterNo,
    String evidenceKind,
    String eventKey,
    Integer beatSeq,
    List<String> entityKeys,
    List<String> threadKeys,
    String fact,
    String source,
    double confidence)

StoryEvidenceIndex(
    Map<Integer,List<StoryEvent>> eventsByChapter,
    Map<Integer,List<StoryObligationEvidence>> obligationsByChapter,
    Map<Integer,List<StoryThread>> threadsByChapter)

StoryEvidenceIndex.builder()
    .addEvent(StoryEvent event)
    .addObligationEvidence(StoryObligationEvidence evidence)
    .addThread(StoryThread thread)
    .build()

StoryEvidenceIndex.hasEvidence(String obligationKey)
StoryEvidenceIndex.evidenceFor(String obligationKey)
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

在 `StoryEvidenceIndexTest` 中定义：

```text
evidenceIndexGroupsByChapter
evidenceIndexFindsObligationEvidence
evidenceIndexRejectsDuplicateEvidenceKey
evidenceIndexDoesNotTreatObligationKeyAsText
```

断言：

- 同一 `obligationKey` 的证据可按章查询；
- 重复证据 key 抛异常；
- 没有证据时 `hasEvidence` 为 false；
- 测试不使用正文字符串包含作为证据。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryEvidenceIndexTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 类型不存在导致失败。

- [ ] **Step 3: 实现证据 record 和索引**

`StoryObligationEvidence` 使用不可变 list copy；`confidence` 限制在 `0..1`。

`StoryEvidenceIndex` 使用 `LinkedHashMap` 保持章号和证据顺序；重复 `obligationKey + evidenceKind + chapterNo + eventKey + beatSeq` 抛 `IllegalArgumentException`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryEvidenceIndexTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 2: 新增义务证据表和数据层

**Files:**

- Create: `novelgen-data/src/main/resources/db/migration-derive/V145__derive_story_obligation_evidence.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/DeriveStoryObligationEvidenceDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/DeriveStoryObligationEvidenceMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveStoryObligationEvidenceMapper.xml`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryObligationEvidencePersistenceTest.java`

**Interfaces:**

- Consumes: Task 1 的 `StoryObligationEvidence`。
- Produces:

```text
DeriveStoryObligationEvidenceMapper.insertFull(DeriveStoryObligationEvidenceDO row)
DeriveStoryObligationEvidenceMapper.deleteByRun(long runId)
DeriveStoryObligationEvidenceMapper.selectByRun(long runId)
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
evidenceRowPersistsJsonbArrays
evidenceDeleteByRunClearsRows
evidenceSelectOrdersByChapter
evidenceUniqueConstraintRejectsDuplicate
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationEvidencePersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现 V145**

```sql
CREATE TABLE derive_story_obligation_evidence (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  obligation_key TEXT NOT NULL,
  chapter_no INTEGER NOT NULL,
  evidence_kind TEXT NOT NULL,
  event_key TEXT,
  beat_seq INTEGER,
  entity_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
  thread_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
  fact TEXT,
  source TEXT NOT NULL,
  confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT,
  UNIQUE(run_id, obligation_key, evidence_kind, chapter_no,
         COALESCE(event_key, ''), COALESCE(beat_seq, -1))
);
CREATE INDEX idx_derive_story_obligation_evidence_run_chapter
  ON derive_story_obligation_evidence(run_id, chapter_no);
```

- [ ] **Step 4: 实现 DO/Mapper/XML**

DO 字段必须与表列一一对应；XML 的 `entity_keys/thread_keys` 必须显式 `::jsonb`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationEvidencePersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 3: 让义务投影保留完整 payload 和依赖

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryObligationProjector.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryProfile.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryObligationPayloadTest.java`

**Interfaces:**

- Consumes: `GenreProfile<C>`、`GenreContract`、`ChapterScheduleEntry`、`VolumeContract`、`CharacterContract`。
- Produces:

```text
StoryObligationProjector.project(
    GenreProfile<?> profile,
    GenreContract contract,
    List<ChapterScheduleEntry> schedule,
    List<VolumeContract> volumes,
    List<CharacterContract> characters) -> List<StoryObligation>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
mysteryPayloadKeepsClueKeyAndPhase
characterSetupPayloadKeepsSetupList
characterCostPayloadKeepsAfterChapter
volumeClosurePayloadKeepsProof
projectorRejectsDuplicateKey
projectorRejectsOutsideSchedule
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryObligationPayloadTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 强制 payload 语义**

义务 payload 必须包含：

```text
PLANT_THREAD/PAYOFF_THREAD:
  clueKey
  phase

INTRODUCE_ENTITY:
  setup

CHARACTER_CHOICE:
  proof

CHARACTER_COST:
  proof
  afterChapter

LOCAL_CLOSURE/FINAL_CLOSURE:
  proof 或 closure
```

- [ ] **Step 4: 修改持久化**

`DeriveChapterScheduleService.persistObligations` 必须使用 `obligation.payload()` 原样写入 DO；禁止在 P6/QC 读取后重建为 `Map.of()`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryObligationPayloadTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 4: 修复 ⑥b 窗口证据抽取和校验

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidator.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/DraftWindowEvidenceValidatorTest.java`

**Interfaces:**

- Consumes: BATCH 输出 JSON、义务、事件、拍 routeContract。
- Produces:

```text
DraftWindowObligationValidator.validate(
    DraftWindow window,
    List<StoryObligation> obligations) -> List<PlanIssue>

DraftWindowObligationValidator.toEvidenceIndex(
    DraftWindow window) -> StoryEvidenceIndex
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
plantRequiresThreadEffectOrBeatEvidence
payoffRequiresThreadEffectOrBeatEvidence
earlyPayoffRejects
duplicatePayoffRejects
missingSetupRejects
stateConflictRejects
knowledgeReadBeforeWriteRejects
outsideEntityRejects
validEvidenceAccepts
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DraftWindowEvidenceValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 从 BATCH JSON 提取证据**

从以下字段抽取：

```text
chapters[].clueEvents
chapters[].state_changes
chapters[].beats[].route.threadEffects
chapters[].beats[].route.knowledgeEffects
chapters[].beats[].filled
```

每个义务的兑现必须至少有一个证据：

```text
PLANT_THREAD → THREAD_EFFECT OPEN 或 beat role CLUE_PLANT
PAYOFF_THREAD → THREAD_EFFECT PAYOFF/CLOSE 或 beat role CLUE_PAYOFF
CHARACTER_CHOICE → EVENT 或 CHAPTER_OUTLINE
CHARACTER_COST → EVENT 或 CHAPTER_OUTLINE，且 afterChapter 的 choice 已存在
FINAL_CLOSURE → EVENT 或明确 TEXT_SPAN
```

- [ ] **Step 4: 修改 DeriveDraftService**

在 `validateObligationWindow` 中：

```text
构建 DraftWindow
→ 构建 StoryEvidenceIndex
→ 调用 validator.validate
→ 失败抛 LlmJson.Bad
```

错误必须包含：

```text
code
chapterNo
obligationKey
evidenceKind
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DraftWindowEvidenceValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 4A: 闭合合同与窗口作用域

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryClosureContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/WindowObligationScope.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryClosureContractTest.java`

**Interfaces:**

- Consumes: `StoryObligation`、`ChapterScheduleEntry`、`StoryContract`。
- Produces:

```text
StoryClosureContract(
    String closureKey,
    int chapterNo,
    String targetKey,
    String kind,
    List<String> requiredActs,
    List<String> requiredTags,
    List<String> proofEventKeys,
    String sourceContract)

WindowObligationScope.of(
    int windowFrom,
    int windowTo,
    List<StoryObligation> obligations)
    -> WindowObligationScope

WindowObligationScope.current()
WindowObligationScope.deferred()
WindowObligationScope.future()
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

```text
closureContractRequiresActsAndTags
windowScopeDefersFutureObligations
windowScopeKeepsCurrentObligations
windowScopeFlagsPastUnfulfilledObligations
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryClosureContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现闭合合同和作用域**

`StoryClosureContract` 的 `requiredActs` 至少允许：

```text
CULPRIT
MOTIVE
METHOD
CHOICE
STATE
```

`WindowObligationScope` 必须把第5章义务在第4章窗口标为 `DEFERRED`，不得提前报 `OBLIGATION_MISSING`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryClosureContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 4B: ⑥b 闭合字段与定向修复

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidator.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/ClosureWindowValidatorTest.java`

**Interfaces:**

- Consumes: `StoryClosureContract`、BATCH 输出的 `closingActs`、`closureEvidence`。
- Produces:

```text
ClosureWindowValidator.validate(
    DraftWindow window,
    StoryClosureContract contract)
    -> List<PlanIssue>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

```text
missingCulpritActRejects
missingMotiveTagRejects
wrongCulpritKeyRejects
futureClosureIsDeferred
validClosureAccepts
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=ClosureWindowValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现闭合校验**

校验结构化字段，不校验正文是否逐字包含标签。

错误：

```text
CLOSURE_WINDOW_MISSING
CLOSURE_TAG_MISMATCH
CLOSURE_CULPRIT_MISMATCH
```

- [ ] **Step 4: 修改 DeriveDraftService**

闭合失败必须返回：

```text
windowNo
chapterNo
obligationKey
missingActs
contractPayload
repairTarget=WINDOW[n]
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=ClosureWindowValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 4C: 字数预算窗口护栏

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/WordBudgetGuard.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/WordBudgetGuardTest.java`

**Interfaces:**

- Consumes: `run.wordsMin`、`run.wordsMax`、窗口字数、剩余章数、章均预算。
- Produces:

```text
WordBudgetGuard.check(
    int totalCharCount,
    int windowCharCount,
    int remainingChapters,
    int chapterAvg,
    int wordsMin,
    int wordsMax)
    -> BudgetDecision

BudgetDecision(
    String code,
    String repairTarget,
    int projectedMin,
    int projectedMax)
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

```text
overBudgetRejects
projectedOverBudgetRejects
projectedUnderBudgetRejects
validBudgetAccepts
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=WordBudgetGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现预算护栏**

错误：

```text
BUDGET_WINDOW_OVERSHOOT
BUDGET_PROJECTED_OVERSHOOT
BUDGET_WINDOW_UNDERSHOOT
```

- [ ] **Step 4: 接入 DeriveDraftService**

每个 BATCH 窗口落库前调用；预算失败只重做当前窗或压缩剩余窗。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=WordBudgetGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 4D: direct 模式不得跳过确定性校验

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DirectModeDeterministicAuditTest.java`

**Interfaces:**

- Consumes: 义务校验、闭合校验、预算护栏、脚本 QC。
- Produces: 现有 `AutoVO` API 不变，但 direct 模式仍执行确定性审计。

**Implementation:**

- [ ] **Step 1: 写失败测试**

```text
directStillRunsObligationWindow
directStillRunsClosureWindow
directStillRunsBudgetGuard
directStillRunsScriptQc
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DirectModeDeterministicAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 修改 direct 分支**

`direct=true` 只跳过 LLM P6/QC 语义审稿，不得跳过：

```text
义务窗口
闭合窗口
预算护栏
脚本 QC
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DirectModeDeterministicAuditTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 5: 构建完整 StoryKernel 装配器

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryKernelAssembler.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryKernelAssemblerTest.java`

**Interfaces:**

- Consumes: `RunDO`、`GenreContract`、`GenreContractCodec<?>`、`ChapterScheduleEntry`、`VolumeContract`、`CharacterContract`、`StoryThreadDO`、`DeriveStoryObligationDO`、`DeriveStoryObligationEvidenceDO`。
- Produces:

```text
StoryKernelAssembler.assemble(
    RunDO run,
    GenreContract contract,
    GenreContractCodec<?> codec,
    List<ChapterScheduleEntry> schedule,
    List<VolumeContract> volumes,
    List<CharacterContract> characters,
    List<StoryThread> threads,
    List<StoryObligation> obligations,
    List<StoryObligationEvidence> evidence) -> StoryKernel
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
assemblerKeepsObligationPayload
assemblerKeepsEvidence
assemblerMapsContractCharactersToEntities
assemblerMapsThreads
assemblerRejectsDuplicateEntityKey
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryKernelAssemblerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现装配器**

`StoryKernelAssembler` 不调用 LLM，不做业务裁决，只负责把数据库合同和证据装配为完整 kernel。

`codec` 负责把通用 `GenreContract` 投影成实体、约束和合同上下文；`MysteryCodec` 可兼容读取旧 `StoryContract`，`RomanceCodec` 不得伪造悬疑字段。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryKernelAssemblerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 6: P6 使用完整 StoryKernel

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateObligationIntegrationTest.java`

**Interfaces:**

- Consumes: Task 5 的 `StoryKernelAssembler`、`GenreContractCodec<?>`、义务和证据 Mapper。
- Produces: 现有 `GateVO` API 不变，但 `genreIssues` 使用完整 kernel。

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
gateReadsObligationPayload
gateReadsEvidence
gateDoesNotUseEmptyKernel
gateReportsMissingEvidence
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveGateObligationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 修改 gate 组装**

替换当前：

```text
new StoryKernel(..., List.of(), Map.of())
```

改为调用：

```text
storyKernelAssembler.assemble(...)
```

`GenreGateAuditor.audit` 和 `MysteryGateAuditor.audit` 必须接收完整 kernel。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveGateObligationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 7: QC 使用证据索引，不再判断字符串

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreQcAuditor.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryQcAuditor.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveQcObligationIntegrationTest.java`

**Interfaces:**

- Consumes: Task 5 的 `StoryKernelAssembler`、`GenreContractCodec<?>`、Task 2 的证据 Mapper、章节正文和 `world_states`。
- Produces: 现有 `QcVO` API 不变，但 `genreQcIssues` 使用证据和正文事件。

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
qcDoesNotUseTargetKeyStringContains
qcAcceptsThreadEffectEvidence
qcRejectsMissingPayoffEvidence
qcRejectsWorldStateConflict
qcReadsObligationPayload
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveQcObligationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 重写 GenreQcAuditor**

删除：

```java
text.contains(obligation.targetKey())
```

替换为：

```text
StoryEvidenceIndex.hasEvidence(obligationKey)
```

同时检查：

```text
正文/事件中的 thread effect
世界状态账
reveal 前的 solution 泄露
```

- [ ] **Step 4: 修改 DeriveQcService**

组装完整 kernel 和 evidence index；不得使用 `Map.of()` 重建义务。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveQcObligationIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 7A: 通用类型合同持久化

**Files:**

- Create: `novelgen-data/src/main/resources/db/migration-derive/V146__derive_genre_contract.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/DeriveGenreContractDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/DeriveGenreContractMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveGenreContractMapper.xml`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreContractCodec.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreContractCodecRegistry.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryCodec.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/GenreContractPersistenceTest.java`

**Interfaces:**

- Consumes: `GenreProfile<C>`、`ScopeContract`、现有 `StoryContract`。
- Produces:

```text
public interface GenreContract {}

public interface GenreContractCodec<C extends GenreContract> {
    String genre();
    JsonNode encode(C contract);
    C decode(JsonNode payload, ScopeContract scope);
}

DeriveGenreContractMapper.insertFull(DeriveGenreContractDO row)
DeriveGenreContractMapper.findByRun(long runId)
GenreContractCodecRegistry.require(String genre) -> GenreContractCodec<?>
DeriveContractService.loadGenreContract(RunDO run) -> GenreContract
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

```text
genreContractPersistsJsonbPayload
genreContractRejectsUnknownGenre
mysteryCodecRoundTripsLegacyStoryContract
romanceCodecDoesNotCreateMysteryFields
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreContractPersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现 V146 和数据层**

```sql
CREATE TABLE derive_genre_contract (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  genre VARCHAR(32) NOT NULL,
  contract_version INTEGER NOT NULL DEFAULT 1,
  scope JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT
);
CREATE UNIQUE INDEX uq_derive_genre_contract_run
  ON derive_genre_contract(run_id);
```

- [ ] **Step 4: 实现泛型接口和 codec**

`MysteryCodec` 可读写旧 `StoryContract`；`RomanceCodec` 只读写 `RomanceContract`。禁止把言情 payload 解析成 `StoryContract`。

`DeriveContractService` 的新 run 路径写 `derive_genre_contract`；`DeriveChapterScheduleService` 通过 codec 读取合同并投影义务。旧 run 读取仍走 `MysteryCodec`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreContractPersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 8: 接入 RomanceProfile

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/RomanceProfile.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/RomanceContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/RomanceCodec.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/RomanceGateAuditor.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/RomanceQcAuditor.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreProfileRegistry.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/RomanceProfileTest.java`

**Interfaces:**

- Consumes: `GenreProfile<RomanceContract>`、`RomanceContract`、`RomanceCodec`、`StoryObligation`、`StoryEvidenceIndex`。
- Produces:

```text
RomanceProfile.genre() -> "ROMANCE"
RomanceProfile.parseContract(JsonNode, ScopeContract) -> RomanceContract
RomanceProfile.validateContract(RomanceContract) -> List<PlanIssue>
RomanceProfile.projectObligations(RomanceContract, schedule) -> List<StoryObligation>
RomanceProfile.auditGate(RomanceContract, kernel, evidence) -> List<PlanIssue>
RomanceProfile.auditQc(RomanceContract, kernel, evidence, draft) -> List<PlanIssue>
RomanceCodec.encode(RomanceContract) -> JsonNode
RomanceCodec.decode(JsonNode, ScopeContract) -> RomanceContract
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
romanceContractHasNoMysteryFields
romanceCodecRoundTripsJsonShape
romanceRejectsMissingStage
romanceRejectsOutOfOrderStage
romanceRejectsUnknownActor
romanceRejectsThreadKeyMismatch
romanceProjectsRelationshipObligations
romanceRejectsEarlyReconciliation
romanceGateAcceptsClosedRelationship
romanceQcRejectsMissingConfession
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=RomanceProfileTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现最小 RomanceProfile**

新增独立合同：

```text
RomanceContract implements GenreContract
```

Java record 结构必须为：

```text
RomanceContract(
    int version,
    ScopeContract scope,
    List<RomanceCharacterSlot> cast,
    RomanceRelationship primaryRelationship,
    Map<RomanceStageKind, RomanceStage> stages,
    List<String> forbidden)

RomanceCharacterSlot(
    String key,
    String name,
    String role,
    String gender,
    String identity)

RomanceRelationship(
    String threadKey,
    String leftKey,
    String rightKey,
    String kind,
    String title)

RomanceStage(
    RomanceStageKind kind,
    int chapterNo,
    String targetKey,
    List<String> actorKeys,
    List<String> threadRefs,
    boolean required,
    String proof,
    Map<String,Object> payload)

RomanceStageKind:
  RELATIONSHIP_START
  MISUNDERSTANDING
  ATTACHMENT_ESCALATION
  CONFLICT_PEAK
  CONFESSION
  RECONCILIATION
  RELATIONSHIP_CLOSURE
```

`RomanceProfile.parseContract` 返回 `RomanceContract`，不得返回或构造 `StoryContract`。

义务投影：

```text
RELATIONSHIP_START → ADVANCE_THREAD
MISUNDERSTANDING → CHARACTER_CHOICE
ATTACHMENT_ESCALATION → CHANGE_STATE
CONFLICT_PEAK → CHARACTER_COST
CONFESSION → PAYOFF_THREAD
RECONCILIATION → LOCAL_CLOSURE
RELATIONSHIP_CLOSURE → FINAL_CLOSURE
```

校验规则：

```text
cast 非空且 key 唯一
leftKey/rightKey 属于 cast
threadKey = REL-{leftKey}-{rightKey}
所有 stage.chapterNo 在 1..scope.chapterCount
stage 章号单调不减
RELATIONSHIP_START 最早
RELATIONSHIP_CLOSURE 最后
CONFESSION 不早于 ATTACHMENT_ESCALATION
RECONCILIATION 不早于 CONFLICT_PEAK
actorKeys 属于 cast
threadRefs 包含 primaryRelationship.threadKey
禁止出现 culprit/motive/method/revealChapter/clue
```

义务 payload 必须包含：

```text
stage
threadKey
actorKeys
proof
relationshipKind
```

`SLOT_ROUTE` 接入：

```text
primaryRelationship.threadKey 进入合法 threadKeys
义务投影为 SYNTHETIC 拍
ADVANCE_THREAD → MAIN_ADVANCE
CHARACTER_CHOICE → CHARACTER_CHOICE
CHANGE_STATE → MAIN_ADVANCE
CHARACTER_COST → COST
PAYOFF_THREAD → RELATION_CONFLICT
LOCAL_CLOSURE → LOCAL_CLOSURE
FINAL_CLOSURE → FINAL_CLOSURE
```

`⑥b` 接入：

```text
输入包含 RomanceContract stages
输出新增 romanceEvents
romanceEvents[].stage 来自 RomanceStageKind
romanceEvents[].evidence 引用 stage.targetKey/threadKey
不得输出 clueEvents/closingActs
```

`P6/QC` 接入：

```text
RomanceGateAuditor 检查阶段顺序、关系开闭和证据
RomanceQcAuditor 检查关系状态变化和阶段证据
不得调用 MysteryGateAuditor/MysteryQcAuditor
不得使用 text.contains(threadKey)
```

- [ ] **Step 4: 注册插件**

`GenreProfileRegistry` 必须同时注册 `MysteryProfile` 和 `RomanceProfile`；注册表接口保持 `GenreProfile<?>`，codec 注册表保持 `GenreContractCodec<?>`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=RomanceProfileTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 9: 旧 run 兼容回归

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/LegacyMysteryAdapter.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/LegacyRunCompatibilityTest.java`

**Interfaces:**

- Consumes: 旧 `STORY_CONTRACT` 快照、旧 `CHAPTER_SCHEDULE`、旧线程和拍表、`MysteryCodec`。
- Produces: 旧 run 的 `MysteryContract`、`StoryKernel`、义务和证据空索引。

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
legacyRunWithoutGenreReadsAsMystery
legacyRunWithoutObligationsStillLoads
legacyRunWithoutEvidenceStillLoads
legacyRunDoesNotRequireV145
legacyRunDoesNotRequireV146
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=LegacyRunCompatibilityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现兼容空证据**

旧 run 没有证据表时：

```text
genre contract = MysteryCodec.decode(旧 STORY_CONTRACT)
StoryEvidenceIndex = empty
StoryKernel.status = LEGACY
旧 P6/QC 行为保持
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=LegacyRunCompatibilityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 10: 悬疑 4 章真实探针

**Files:**

- Create: `var/derive-probe-mystery-v2.json`
- Create: `var/derive-probe-mystery-v2-report.md`

**Interfaces:**

- Consumes: 本地或宝塔 derive API、登录 cookie、V143/V144/V145 迁移后的数据库。
- Produces: 真实 run 证据。

**Implementation:**

- [ ] **Step 1: 固定探针参数**

```json
{"genre":"MYSTERY","wordsMin":3000,"wordsMax":5000,"tags":["悬疑"],"pace":"0.75","seed":450067,"lenCheck":false}
```

- [ ] **Step 2: 登录、建 run、启动 auto**

请求必须带：

```text
Origin: https://zzdzz.cn:8888
```

- [ ] **Step 3: 记录证据**

报告必须包含：

```text
runId
genre
contractVersion
义务数量
证据数量
窗口校验结果
P6 结果
正文章数
QC 结果
失败原因
```

- [ ] **Step 4: 验收**

必须证明：

```text
义务 payload 落库
义务证据落库
提前 payoff 在 P6 前被拦截
P6 使用完整 kernel
QC 不用 targetKey 字符串包含
至少 4 章正文非空
```

- [ ] **Step 5: 写报告**

报告写入 `var/derive-probe-mystery-v2-report.md`，不得包含 API key 或口令。

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 11: 言情 4 章真实探针

**Files:**

- Create: `var/derive-probe-romance-v2.json`
- Create: `var/derive-probe-romance-v2-report.md`

**Interfaces:**

- Consumes: `RomanceProfile`、derive API、登录 cookie。
- Produces: 非悬疑探针报告。

**Implementation:**

- [ ] **Step 1: 固定探针参数**

```json
{"genre":"ROMANCE","wordsMin":3000,"wordsMax":5000,"tags":["言情"],"pace":"0.50","seed":450068,"lenCheck":false}
```

- [ ] **Step 2: 登录、建 run、启动 auto**

- [ ] **Step 3: 验收**

必须证明：

```text
合同没有 culprit/motive/method/revealChapter
义务包含关系线/选择/代价/闭合
⑥b 窗口校验可运行
P6 可运行
至少 4 章正文非空
QC 报告存在
```

- [ ] **Step 4: 写报告**

报告写入 `var/derive-probe-romance-v2-report.md`。

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Final Verification

全部任务完成后按顺序执行：

```text
mvn -q test
mvn -q -DskipTests package
git diff --check
```

最终必须证明：

- 通用核心没有悬疑专属字段；
- MysteryProfile 和 RomanceProfile 都接入；
- 义务 payload 不再丢失；
- 义务证据真实落库；
- ⑥b 能在 P6 前拦截窗口级结构错误；
- P6 使用完整 StoryKernel；
- QC 使用证据索引，不使用字符串包含；
- 新类型合同使用 `derive_genre_contract`；
- RomanceContract 不包含也不伪造悬疑字段；
- 旧 run 可读取；
- 两个真实探针有报告；
- 没有未经授权的 commit、push、部署。
