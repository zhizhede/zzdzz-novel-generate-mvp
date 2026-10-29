# DeriveFlow 阶段 4 正文闭环修复计划

**Goal:** 修复阶段 4 的正文完成、终检和阶段账本状态语义，使 DRAFT、FINAL_AUDIT、COMPLETE 的状态与实际产物一致。

**Architecture:** 只修复现有正文闭环模块，不新增计划编译能力、不改数据库 schema、不修改 StoryContract 或 PlanSnapshot。`FinalAuditService` 负责审计报告，`FinalAuditStageHandler` 负责把 BLOCKER 转成阶段失败，`DraftStageHandler` 只有在全部章节完成后才能标记 DRAFT DONE。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Jackson、JUnit 5。

**Spec:** 本文件；实施前必须读取 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`。

## Global Constraints

- 不改主站 `novelgen`。
- 未获用户当轮允许，禁止 commit、push、部署、改线上库、重启线上服务。
- 管线运行中禁止 `mvn clean`、禁止重启后端。
- 不修改 `StoryContract`、`CompiledStoryPlan`、`PlanSnapshotService` 或 `run.active_plan_revision`。
- 不新增迁移；本修复只改 Java、测试和必要的状态展示。
- 任何异常不得被改写成“正文为空”；错误必须保留原 code/path。
- 未满足 Pass Criteria 前，不得进入阶段 5。

## Review Focus

- BLOCKER 终检必须使 `FinalAuditStageHandler` 失败，不能只把 issue 放在 `quality=PASS` 中。
- DRAFT stage 只有在 `missingChapters` 为空时才能 DONE。
- active revision 读取失败必须是 `FINAL_AUDIT_BLOCKER`，不能报告 PASS。
- DB、契约、NPE 等非正文错误不得被包装成 `DRAFT_CHAPTER_EMPTY`。
- `SceneDraftException` 死代码和重复分支必须移除。

## Task F1：修正终检质量语义

### Boundary

只修改 `FinalAuditReport`、`FinalAuditService`、`FinalAuditStageHandler` 和对应测试；不修改正文生成。

### Why

当前代码在发现 BLOCKER 时仍返回 `FinalQuality.PASS`，测试甚至断言缺章时 quality 为 PASS。这会让只读 quality 的调用方误放行。

### Inputs

- `FinalAuditReport`
- `PlanAuditIssue.Severity`
- `FinalAuditService.audit()`
- `FinalAuditStageHandler`

### Outputs

- `FinalAuditReport.FinalQuality` 增加 `FAILED`；
- BLOCKER 路径返回 `FAILED`；
- active revision 读取失败返回 `FAILED`；
- FinalAuditStageHandler 继续把 BLOCKER 转为 `StageOutcome.failed`；
- 测试不再断言 BLOCKER 时 quality 为 PASS。

### Required Files

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditReport.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandler.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FinalAuditServiceTest.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandlerTest.java`

### Required Tests

```java
void missingChapterProducesFailedQualityAndBlockerIssue()
void emptyChapterProducesFailedQualityAndBlockerIssue()
void metaLeakProducesFailedQualityAndBlockerIssue()
void activeRevisionFailureProducesFailedQuality()
void advisoryParagraphProducesAdvisoryQuality()
void finalAuditHandlerReturnsFailedStageOutcomeForBlocker()
void finalAuditHandlerDoesNotReturnDoneForFailedReport()
```

### Steps

- [ ] **Step 1:** 写失败测试，确认当前 `missingChapterIsBlocker` 仍断言 PASS。
- [ ] **Step 2:** 运行：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FinalAuditServiceTest,FinalAuditStageHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3:** 将 `FinalQuality` 扩展为 `PASS, ADVISORY, DEGRADED, FAILED`。
- [ ] **Step 4:** 将 `FinalAuditService` 的 active revision 异常、run 缺失、BLOCKER 路径全部改为 `FAILED`。
- [ ] **Step 5:** 更新 `FinalAuditStageHandler`，确保失败报告不会落 DONE。
- [ ] **Step 6:** 运行同一命令，全部 PASS。

## Task F2：让 DRAFT stage 在正文不完整时失败

### Boundary

只修改 `DraftStageHandler`、`FullDraftCompletionService` 和测试；不改变章节生成算法。

### Why

当前 `FullDraftCompletionService.completeAll()` 可以返回 `missingChapters`，但 `DraftStageHandler` 无条件标记 DRAFT DONE。阶段账本会错误地显示正文完成，后续只有 FINAL_AUDIT 才发现问题。

### Inputs

- `FullDraftCompletionService.DraftCompletionResult`
- `DraftStageHandler`
- `StageOutcome`
- `DeriveStageRunMapper`

### Outputs

- `DraftStageHandler`：
  - `missingChapters` 为空 → `StageOutcome.done`
  - `missingChapters` 非空 → `StageOutcome.failed("DRAFT_INCOMPLETE", path, message)`
- `FullDraftCompletionService` 的结果保留 missing chapter 列表；
- stage ledger 不再把不完整正文写成 DONE。

### Required Files

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionService.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandlerTest.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionServiceTest.java`

### Required Tests

```java
void completeAllWithEmptyMissingReturnsDoneStageOutcome()
void completeAllWithMissingChaptersReturnsFailedStageOutcome()
void failedDraftStageDoesNotAdvanceToFinalAudit()
void completedDraftStagePersistsCompletedRevision()
```

### Steps

- [ ] **Step 1:** 写失败测试，确认当前 DRAFT 在 missing 非空时仍 DONE。
- [ ] **Step 2:** 运行：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DraftStageHandlerTest,FullDraftCompletionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3:** 在 handler 中根据 `missingChapters` 分支处理。
- [ ] **Step 4:** 确认失败 path 使用 `chapter[<missingNo>]`。
- [ ] **Step 5:** 运行同一命令，全部 PASS。

## Task F3：停止吞掉正文阶段异常

### Boundary

只修改 `FullDraftCompletionService`、`SceneDraftException` 和测试；不新增数据库或前端。

### Why

当前 `completeAll()` 捕获所有 `RuntimeException` 并改写成 `DRAFT_CHAPTER_EMPTY`。数据库故障、NPE、契约错误会伪装成模型没写正文，破坏故障定位和恢复。

### Inputs

- `SceneDraftException`
- `BizException`
- `RuntimeException`
- `ChapterDraftCompletionService`

### Outputs

- 只有 scene 生成失败使用 `DRAFT_CHAPTER_EMPTY`；
- `PLAN_REVISION_STALE` 继续抛出；
- 数据库异常、NPE、契约异常保留原异常类别；
- scene failure 不产生死代码。

### Required Files

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/SceneDraftException.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionServiceTest.java`

### Required Tests

```java
void staleRevisionExceptionIsRethrown()
void sceneDraftExceptionIsRetriedThenRecorded()
void databaseRuntimeExceptionIsRethrown()
void contractRuntimeExceptionIsRethrown()
void nullPointerRuntimeExceptionIsRethrown()
void chapterFailureDoesNotBecomeDRAFT_CHAPTER_EMPTY()
```

### Steps

- [ ] **Step 1:** 写失败测试，确认数据库异常当前被包装成 `DRAFT_CHAPTER_EMPTY`。
- [ ] **Step 2:** 运行：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FullDraftCompletionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3:** 将捕获逻辑改为只捕获预期 scene 失败。
- [ ] **Step 4:** 保留 stale revision 的直接抛出。
- [ ] **Step 5:** 运行同一命令，全部 PASS。

## Task F4：回归验证与状态读模型

### Boundary

只运行已有阶段 4 模块的测试并验证报告；不新增业务实现。

### Why

F1–F3 修复了多个相邻状态语义。只有回归验证后，才能判断阶段 4 是否真正满足可进入阶段 5 的条件。

### Required Files

- `docs/STATUS.md`
- `docs/architecture/pipeline-contracts.md`

### Required Tests

```text
FinalAuditServiceTest
FinalAuditStageHandlerTest
DraftStageHandlerTest
FullDraftCompletionServiceTest
ChapterDraftCompletionServiceTest
SceneIsolationCompletionServiceTest
StageRunStateServiceTest
DeriveRunOrchestratorTest
```

### Steps

- [ ] **Step 1:** 运行：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FinalAuditServiceTest,FinalAuditStageHandlerTest,DraftStageHandlerTest,FullDraftCompletionServiceTest,ChapterDraftCompletionServiceTest,SceneIsolationCompletionServiceTest,StageRunStateServiceTest,DeriveRunOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 2:** 运行全量测试：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
```

- [ ] **Step 3:** 验证报告不再出现 BLOCKER + PASS、missing + DRAFT DONE、数据库异常被改写为空正文。
- [ ] **Step 4:** 更新 `docs/STATUS.md`，记录修复证据、偏差和是否可进入阶段 5。

## Definition of Done

- [ ] BLOCKER 终检 quality 为 FAILED；
- [ ] active revision 失败 quality 为 FAILED；
- [ ] DRAFT 有 missing chapter 时 stage 为 FAILED；
- [ ] DRAFT 无 missing chapter 时才为 DONE；
- [ ] 数据库/契约异常不再被包装为 `DRAFT_CHAPTER_EMPTY`；
- [ ] 阶段 4 定向测试与全量测试通过；
- [ ] 未进入阶段 5；
- [ ] 未 commit/push/deploy。
