# DeriveFlow 正文闭环与持久编排 Implementation Plan

> **Superseded:** 本文件的任务编号与跨 Task 依赖已废弃。新的主执行计划见 [2026-10-29-derive-refactor-single-pass-plan.md](2026-10-29-derive-refactor-single-pass-plan.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Execute after `2026-10-28-derive-plan-compiler-admission.md`.

**Goal:** 从已准入的不可变计划出发，按章、按场景生成全部正文；正文失败只局部修复，阶段可持久恢复，最终一定完成所有规定章节。

**Architecture:** `SceneWriter` 只写单场散文，`ChapterAssembler` 组合章节，`ChapterDraftCompletionService` 驱动逐章闭环，`DeriveRunOrchestrator` 用法持久 stage ledger 驱动 `CONTRACT → PLAN → ADMIT → DRAFT → FINAL_AUDIT`。正文阶段禁止修改计划 revision。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、JUnit 5、Vue 3。

**Spec:** `docs/superpowers/plans/2026-10-28-derive-typed-plan-compiler-refactor.md`。

## Global Constraints

- 必须完成输出协议与计划编译子计划后再开始。
- 未获用户当轮允许，禁止 commit/push/deploy。
- 新增迁移从 `V140` 开始，不改历史迁移。
- 正文阶段不得修改 `StoryContract`、`PlanSnapshot` 或 `run.active_plan_revision`。
- 正文模型只输出散文；禁止再输出追加 JSON 契约。
- 单章/单场失败只局部重试；禁止全书退回 `SCALE/SPINE`。
- 失败原因、attempt、lease 和阶段状态必须落库。
- 不新增主站行为，不改 `novelgen`。

## Review Focus

- 已准入计划在正文过程中不得发生 hash/revision 变化。
- 某场景失败后后续场景仍可继续，最终章节必须有可用文本。
- 第 N 章失败不能让第 N+1 章永久不可达。
- 服务重启后从最后完成 stage 恢复。
- 10 个 fresh run 必须全部到达 `DRAFT_COMPLETE`。

## Canonical Error Codes

| code | 含义 |
|---|---|
| `STAGE_CLAIM_CONFLICT` | 阶段已被其他 owner 抢占 |
| `STAGE_LEASE_EXPIRED` | lease 过期，结果不得提交 |
| `STAGE_HANDLER_MISSING` | stage 没有注册 handler |
| `DRAFT_PLAN_REVISION_STALE` | 正文产物与 active plan revision 不一致 |
| `DRAFT_SCENE_EMPTY` | 场景所有候选均为空 |
| `DRAFT_SCENE_INVALID` | 场景输出含机器 JSON/过程话 |
| `DRAFT_CHAPTER_EMPTY` | 章节组装后为空 |
| `DRAFT_DEGRADED` | 使用最佳候选但未完全达标 |
| `FINAL_AUDIT_BLOCKER` | 终检发现缺章/空章/计划漂移 |

---

### Task C1: V140 持久阶段执行账

**Boundary:** 只建立 V140 stage ledger、DO 和 Mapper，不实现编排逻辑。

**Why:** 让长任务状态、attempt、lease 和错误路径可持久恢复，不再只依赖内存。

**Inputs:** V140 DDL 和阶段执行字段。

**Outputs:** derive_stage_run 表、DO、Mapper、XML。

**Pass Criteria:** 唯一键、条件 claim、finish 写入 code/path/revision 的测试通过。

**Files:**
- Create: `novelgen-data/src/main/resources/db/migration-derive/V140__derive_stage_run.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/DeriveStageRunDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/DeriveStageRunMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveStageRunMapper.xml`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DeriveStage.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/DeriveStageRunMapperTest.java`

**Interfaces:**
- Produces:

```java
interface DeriveStageRunMapper extends BaseMapper<DeriveStageRunDO> {
    long insertFull(DeriveStageRunDO row);
    DeriveStageRunDO findLatest(long runId, String stage);
    List<DeriveStageRunDO> findOpen(long runId);
    int claim(long runId, String stage, int attempt,
              String leaseOwner, OffsetDateTime leaseUntil);
    int finish(long id, String state, String code, String path,
               String message, Integer outputRevision);
}
```

data 模块不得依赖 service 枚举；`StageRunStateService` 用 `DeriveStage.name()` 调 Mapper。

- [ ] **Step 1: 写 Mapper 测试**

```java
void insertAndFindLatestStage()
void claimOnlyPendingStage()
void finishWritesCodePathAndRevision()
void uniqueRunStageAttemptIsEnforced()
```

- [ ] **Step 2: 写 V140 迁移**

使用主计划第 4.2 节 DDL。只新增表/索引/约束。

- [ ] **Step 3: 实现 DO/Mapper/XML**

状态条件更新必须返回影响行数；JSONB 本任务不涉及。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DeriveStageRunMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C2: StageRunState 与持久恢复策略

**Boundary:** 只管理阶段状态机、顺序、lease 和恢复，不实现各阶段业务。

**Why:** 把长流程恢复规则独立出来，避免 orchestrator 混合业务与租约。

**Inputs:** stage rows、owner、leaseUntil。

**Outputs:** StageClaim、StageRunStateService、按固定顺序 claim/complete/fail/cancel。

**Pass Criteria:** 不允许双 owner、过期可恢复、失败重试、取消不再推进。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/StageRunState.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/StageClaim.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/StageRunStateService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/StageRunStateServiceTest.java`

**Interfaces:**
- Produces:

```java
enum DeriveStage {
    CONTRACT, COMPILE_PLAN, EXPAND_SCRIPTS, ADMIT_PLAN,
    DRAFT, FINAL_AUDIT, COMPLETE
}

enum StageStatus { PENDING, RUNNING, DONE, FAILED, CANCELLED }

public record StageClaim(
        long stageRunId, DeriveStage stage, int attempt,
        String leaseOwner, OffsetDateTime leaseUntil) {}

interface StageRunStateService {
    StageClaim claimNext(long runId, String owner);
    void done(StageClaim claim, Integer outputRevision);
    void failed(StageClaim claim, String code, String path, String message);
    void cancelled(StageClaim claim);
}
```

- [ ] **Step 1: 写状态机测试**

```java
void firstStageIsContract()
void nextStageFollowsDependencyOrder()
void runningStageCannotBeClaimedBySecondOwner()
void expiredLeaseCanBeReclaimed()
void failedStageIsRetriedWithNextAttempt()
void cancelledStageNeverAdvances()
```

- [ ] **Step 2: 实现 stage 顺序**

固定顺序：

```text
CONTRACT → COMPILE_PLAN → EXPAND_SCRIPTS → ADMIT_PLAN
         → DRAFT → FINAL_AUDIT → COMPLETE
```

`ADMIT_PLAN` 必须读取 `EXPAND_SCRIPTS` 产出的最新 DRAFT revision；
不得准入结构尚未扩写或脚本缺少 scene 的 revision。

- [ ] **Step 3: 实现 lease**

默认 lease 10 分钟；owner 为 host:pid:uuid；同一 run 只允许一个 RUNNING stage。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=StageRunStateServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C3: DeriveRunOrchestrator

**Boundary:** 只做阶段调度，不实现具体 handler。

**Why:** 把控制流固定成可测试的 dispatcher，业务由独立模块接入。

**Inputs:** StageRunStateService、DeriveStageHandlerRegistry。

**Outputs:** advance/cancel/status 和每阶段一次 handler 调用。

**Pass Criteria:** 单次 advance 只推进一个 stage，失败/取消/重启测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DeriveRunOrchestrator.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DeriveStageHandler.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DeriveStageHandlerRegistry.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/StageOutcome.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/DeriveRunOrchestratorTest.java`

**Interfaces:**
- Consumes: `StageRunStateService`、`StoryPlanCompiler`、`PlanAdmissionService`、`ChapterDraftCompletionService`
- Produces:

```java
interface DeriveRunOrchestrator {
    AutoVO advance(long runId);
    AutoVO cancel(long runId);
    AutoVO status(long runId);
}

interface DeriveStageHandler {
    DeriveStage supports();
    StageOutcome execute(StageClaim claim);
}

record StageOutcome(boolean done, Integer outputRevision,
                    String errorCode, String errorPath, String errorMessage) {
    public static StageOutcome done(Integer outputRevision);
    public static StageOutcome failed(String code, String path, String message);
}

final class DeriveStageHandlerRegistry {
    public void register(DeriveStageHandler handler);
    public DeriveStageHandler require(DeriveStage stage);
}
```

- [ ] **Step 1: 写编排测试**

```java
void advanceRunsExactlyOneStagePerCall()
void failedStageStopsWithoutAdvancing()
void cancelStopsAfterCurrentLeaseStep()
void statusComesFromDatabaseNotMemory()
void restartResumesFromFirstNonDoneStage()
```

- [ ] **Step 2: 实现 switch**

每个 stage 通过 registry 找唯一 handler；不得在 orchestrator 内写 SQL 或 JSON 解析。
测试使用 fake handlers，验证每 stage 恰好执行一次；真实 handlers 在后续 task 注册。

- [ ] **Step 3: 改造 DeriveAutoFlowService**

保留 `start/stop/kill/status` 端点，内部委托 orchestrator；不再维护独立的业务 `lastError` 事实。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DeriveRunOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C4: Scene/Chapter 数据类型与 assembler

**Boundary:** 只定义 Scene/Chapter 数据并组装文本，不调用 LLM。

**Why:** 把章节拼接变成确定性的纯代码，避免把结构拼装交给模型。

**Inputs:** ChapterScript 和 SceneDraft。

**Outputs:** 按 sceneNo 组装的非空 ChapterDraftResult。

**Pass Criteria:** 缺场景、空场景、顺序错误和机器 JSON 被拒绝，测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneScript.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneContext.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneDraft.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/ChapterScript.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/ChapterDraftResult.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/ChapterAssembler.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/ChapterAssemblerTest.java`

**Interfaces:**
- Produces:

```java
record SceneScript(int sceneNo, String beatKey, String goal,
                   int budget, List<String> carryKnowledge,
                   List<String> revealKnowledge) {}
record SceneContext(long runId, int planRevision, int chapterNo,
                    String priorSummary, Map<String, List<String>> stateBefore) {}
record SceneDraft(int sceneNo, String text, int chars) {}
record ChapterScript(int chapterNo, String title, int budget, List<SceneScript> scenes) {}
record ChapterDraftResult(int chapterNo, String text, int chars,
                          List<SceneDraft> scenes) {}

ChapterDraftResult assemble(ChapterScript chapter, List<SceneDraft> scenes);
```

- [ ] **Step 1: 写 assembler 测试**

```java
void assemblesScenesInSceneNumberOrder()
void missingSceneFailsChapterAssembly()
void emptySceneFailsChapterAssembly()
void chapterTextExcludesMachineJson()
void charCountIgnoresWhitespaceNormalization()
```

- [ ] **Step 2: 实现 assembler**

输入按 sceneNo 排序；不加解释、不加章注 JSON；章节正文只由场景文本和必要空行组成。

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=ChapterAssemblerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C5: SceneWriter 散文生成

**Boundary:** 只生成单场散文，不修改计划或章节状态。

**Why:** 将长文生成限制到小上下文，减少结构化错误和跨场污染。

**Inputs:** SceneScript、SceneContext、文风和连续性上下文。

**Outputs:** 纯散文 SceneDraft 或明确错误。

**Pass Criteria:** 输出无 JSON 尾巴、非空、非占位；prompt 只包含当前场景上下文。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneWriter.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/SceneWriterTest.java`

**Interfaces:**
- Produces:

```java
SceneDraft write(SceneScript script, SceneContext context);
```

- [ ] **Step 1: 写输出测试**

```java
void writerPromptContainsOnlyCurrentScene()
void writerOutputMustBePlainProse()
void machineJsonTailIsRejected()
void emptyOutputIsRejected()
void tinyPlaceholderOutputIsRejected()
```

- [ ] **Step 2: 实现 prompt**

Prompt 包含：

- 当前 scene goal；
- carry/reveal knowledge；
- current state；
- prior chapter summary；
- style constraints；
- 禁止解释和 JSON。

- [ ] **Step 3: 实现纯文本提取**

只剥离 markdown fence/首尾空白；不得解析场景正文中的 JSON。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=SceneWriterTest,DeriveWritePlaceholderTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C6: 场景级重试与降级策略

**Boundary:** 只规定场景重试次数和 best candidate，不负责章节或全书控制。

**Why:** 确保单场失败不会直接终止全书，并优先保留内容干净而非字数接近的候选。

**Inputs:** SceneWriter、SceneScript、SceneContext、maxAttempts。

**Outputs:** SceneRetryResult、attempt 记录、degraded 标记。

**Pass Criteria:** 首次成功停止；失败反馈精确；全空返回 null；最佳候选规则测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneRetryPolicy.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/SceneRetryPolicyTest.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneWriter.java`

**Interfaces:**
- Produces:

```java
public final class SceneRetryPolicy {
    public record SceneAttempt(int attempt, SceneDraft draft, List<String> issues) {}
    public record SceneRetryResult(SceneDraft best, int attempts, boolean degraded,
                                   List<String> lastIssues) {}
    public SceneRetryResult writeWithRetry(
            SceneScript script, SceneContext context, int maxAttempts);
}
```

- [ ] **Step 1: 写重试测试**

```java
void firstGoodAttemptStopsRetry()
void secondAttemptUsesExactFirstFailure()
void maxAttemptsReturnsBestNonemptyDraft()
void allAttemptsEmptyReturnsNoDraft()
void degradedDoesNotThrow()
```

- [ ] **Step 2: 实现 best candidate**

评分优先级：

1. 非空；
2. 不含机器 JSON/过程话；
3. 字数接近 scene budget；
4. 较新的合格 attempt 优先。

若全空，返回 `best=null`，由章节循环决定是否阻断当前章。

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=SceneRetryPolicyTest,SceneWriterTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C7: ChapterDraftCompletionService

**Boundary:** 只完成一个章节并持久化场景/章节文本，不推进其他章节。

**Why:** 建立可重入的章节单元，确保重启后复用已完成 scene 且不混用旧计划。

**Inputs:** ADMITTED snapshot、SceneWriter、ChapterAssembler、带 plan_revision 的 mapper。

**Outputs:** ChapterDraftResult、beat_instance.filled_text、chapters.content。

**Pass Criteria:** 每 scene 只写当前 revision；0 行更新报 stale；章节组装后非空。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/ChapterDraftCompletionService.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/ChapterMapper.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/BeatInstanceMapper.java`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveChapterMapper.xml`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveBeatInstanceMapper.xml`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/ChapterDraftCompletionServiceTest.java`

**Interfaces:**
- Consumes: `PlanSnapshotService.requireActive`、`SceneWriter`、`ChapterAssembler`、
  `BeatInstanceMapper.updateFilledIfRevision`、`ChapterMapper.updateContentIfRevision`
- Produces:

```java
boolean chapterComplete(long runId, int chapterNo);
ChapterDraftResult completeChapter(long runId, int chapterNo);
```

- [ ] **Step 1: 写单章测试**

```java
void readsOnlyAdmittedPlanRevision()
void persistsCompletedChapterText()
void doesNotModifyPlanSnapshot()
void sceneFailureDoesNotChangePlanRevision()
void resumingChapterReusesCompletedScenes()
void stalePlanRevisionCannotOverwriteSceneText()
void stalePlanRevisionCannotOverwriteChapterText()
```

- [ ] **Step 2: 实现 chapter 阶段**

顺序：

1. 读 ADMITTED snapshot；
2. 读取已完成 scene；
3. 只生成缺失 scene；
4. assembler；
5. 每个 SceneDraft 写回对应 `beat_instance.filled_text`，条件必须包含
   `run_id`、`beat_key`、`plan_revision`，成功后 `status='WRITTEN'`；
6. assembler 后写 `chapters.content`，条件必须包含 `novel_id`、`chapter_no`、`plan_revision`，
   成功后章 `status='DRAFTED'`；
7. 返回 result。

若任一条件更新影响 0 行，抛 `PLAN_REVISION_STALE`，不得覆盖旧计划正文。

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=ChapterDraftCompletionServiceTest,ChapterAssemblerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C8: 全书正文闭环

**Boundary:** 只顺序推进所有章节并汇总完成度，不运行 FinalAudit。

**Why:** 保证一章失败不会阻断后续章，最终能准确报告完成与缺失章节。

**Inputs:** FullDraftCompletionService、ChapterDraftCompletionService、active snapshot。

**Outputs:** DraftCompletionResult 和每章正文/缺失列表。

**Pass Criteria:** 遍历全部章、失败章记录、后章继续、计划 revision 不变，测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionServiceTest.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveWriteService.java`

**Interfaces:**
- Produces:

```java
public final class FullDraftCompletionService {
    public record DraftCompletionResult(
            int planRevision, int chaptersExpected,
            int chaptersCompleted, int chaptersDegraded,
            List<Integer> missingChapters) {}
    public DraftCompletionResult completeAll(long runId);
}
```

- [ ] **Step 1: 写全章测试**

```java
void completesEveryChapterNonempty()
void oneDegradedChapterDoesNotStopLaterChapters()
void laterChapterStillRunsAfterEarlierFailure()
void finalResultCountsEveryChapter()
void chapterWithNoUsableSceneIsListedInMissingChapters()
void draftDoesNotCallPlanReplanner()
```

- [ ] **Step 2: 实现循环**

按章号升序调用 `completeChapter`；已存在非空正文则跳过；章节失败最多重试当前章，然后继续下一章。
本任务只返回 `DraftCompletionResult`，不调用 FinalAudit；FINAL_AUDIT 由 orchestrator 的下一 stage 触发。

`DraftStageHandler.supports()` 返回 `DRAFT`；`execute` 读取 active revision 并调用
`FullDraftCompletionService.completeAll`。完成后返回 `StageOutcome.done(activeRevision)`。

- [ ] **Step 3: 删除正文阶段结构重写**

不得调用 `resetForRepair`、`replanVolume`、`replanFrom`。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FullDraftCompletionServiceTest,ChapterDraftCompletionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C9: FinalAudit 与质量分级

**Boundary:** 只读审计最终正文和 manifest，不修改正文或计划。

**Why:** 完成全套硬检查和软质量分级，为 final stage 提供可判定结果。

**Inputs:** active revision、全部章节、scene 状态。

**Outputs:** FinalAuditReport(PASS/ADVISORY/DEGRADED) 和 issues。

**Pass Criteria:** 缺章/空章/revision drift 为 BLOCKER；风格问题 advisory；不写计划。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditReport.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandler.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FinalAuditServiceTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveQcServiceContractTest.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java`

**Interfaces:**
- Produces:

```java
FinalAuditReport audit(long runId);

public record FinalAuditReport(
        long runId, int planRevision, FinalQuality quality,
        List<PlanAuditIssue> issues) {
    public enum FinalQuality { PASS, ADVISORY, DEGRADED }
}
```

- [ ] **Step 1: 写审计测试**

```java
void missingChapterIsBlocker()
void repeatedMetaLeakIsBlocker()
void styleIssueIsAdvisory()
void finalAuditDoesNotModifyPlan()
```

- [ ] **Step 2: 实现硬/软分级**

- 硬失败：章节缺失、空正文、禁词、计划 revision 变化；
- advisory：文笔、节奏、重复片段；
- degraded：某 scene 使用最佳候选但未达标。

`FinalAuditStageHandler.supports()` 返回 `FINAL_AUDIT`；审计完成后 stage 标 DONE，
随后 orchestrator 才进入 `COMPLETE`。

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FinalAuditServiceTest,DeriveQcServiceContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task C10: 状态读模型与前端正文进度

**Boundary:** 只暴露持久进度读模型和前端展示，不改变执行状态。

**Why:** 让用户可靠看到当前 stage、章节完成数、degraded 和错误路径，而不是内存中的旧阶段。

**Inputs:** run、stage ledger、active plan revision、章节统计。

**Outputs:** RunDetailVO.DraftProgress 和前端展示。

**Pass Criteria:** 重启后投影仍正确，失败 code/path 可见，前端构建通过。

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQueryService.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/RunDetailVO.java`
- Modify: `web/src/views/derive/DeriveRunDetailView.vue`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveRunProgressProjectionTest.java`

**Interfaces:**
- Produces:

```java
record DraftProgress(
        String stage, String state, int planRevision,
        int chaptersExpected, int chaptersCompleted,
        int chaptersDegraded, String lastErrorCode,
        String lastErrorPath) {}
```

- [ ] **Step 1: 写投影测试**

断言运行中/重启/失败状态都来自 stage ledger，不从内存状态或 `step_status` 猜。

- [ ] **Step 2: 更新前端**

显示：

- 当前 stage；
- plan revision；
- 已完成/总章节；
- degraded 数；
- 失败 code/path。

- [ ] **Step 3: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DeriveRunProgressProjectionTest" "-Dsurefire.failIfNoSpecifiedTests=false"
cd web
npm run build
```

Expected: PASS。

---

### Task C11: 全流程验收与部署门

**Boundary:** 只做最终验收、故障注入和授权后的部署门，不新增业务实现。

**Why:** 用真实 run 证明全链路可完成，而不是用单元测试代替实弹。

**Inputs:** A/B/C 全部实现、10 个 fixture、授权和部署前检查。

**Outputs:** 全量测试/构建、10 个 fresh-run 结果、部署证据（若授权）。

**Pass Criteria:** 所有规定章节非空，P6/plan admission 首轮准入，revision 不变，run 到 DRAFT_COMPLETE。

**Files:**
- Modify: `docs/STATUS.md`
- Modify: `docs/architecture/pipeline-contracts.md`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/e2e/DeriveCompilerAcceptanceTest.java`

**Interfaces:**
- Consumes: Tasks C1–C10
- Produces: 完整 fresh-run 完成证据

- [ ] **Step 1: 全量测试与构建**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
mvn -q -pl novelgen-derive-app -am package -DskipTests
cd web
npm run build
```

Expected: 全部退出码 0。

- [ ] **Step 2: 跑 10 个 fresh-run fixture**

覆盖单卷悬疑、非推理单卷、多卷、有无样本、不同 seed、12–18K 字、8–15 章、reveal 合同和同章多 payoff。

- [ ] **Step 3: 每个 run 断言**

```text
StoryContract = 1 FROZEN
PlanSnapshot = 1 ADMITTED
schema violations = 0
plan hard issues = 0
all clue plant/payoff correct
all chapters nonempty
active_plan_revision unchanged during DRAFT
run reaches DRAFT_COMPLETE
```

- [ ] **Step 4: 故障注入**

离线覆盖：

- unknown role；
- duplicate state key；
- reveal 无 payoff；
- beat 错章；
- 空章节；
- 服务重启；
- 正文 cancel。

- [ ] **Step 5: 部署门**

只有用户当轮明确授权后才部署。部署前记录旧 jar SHA256、确认无活动 run、备份 jar；部署后检查 Flyway 139/140、8091、health，并创建全新 run。任何计划准入失败立即停止并记录证据，不热修、不放宽 schema。

---

## Completion Check

- [ ] `derive_stage_run` 持久记录每个阶段 attempt/lease/error path。
- [ ] orchestration status 不依赖内存 `lastError`。
- [ ] 正文 writer 输出纯散文。
- [ ] 单 scene/单章失败不阻断后续章节。
- [ ] 每章完成后持久化正文。
- [ ] FinalAudit 不改变 plan revision。
- [ ] 10 个 fresh run 全部到达 `DRAFT_COMPLETE`。
