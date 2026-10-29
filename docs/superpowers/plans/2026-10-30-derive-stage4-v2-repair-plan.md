# DeriveFlow 阶段 4 V2 修复计划

**日期：** 2026-10-30  
**适用阶段：** 阶段 4「正文闭环」  
**状态：** 待独立 Worker 执行，执行后必须由独立 Reviewer 审查  

## 0. 结论与方向

阶段 4 的总体方向是正确的：正文绑定不可变计划快照、失败只做局部重试、阶段状态持久化、终检只读。当前问题不是重新设计整条随机衍生管线，而是两个跨方法边界没有收口：

1. `DraftStageHandler` 预读一次 active revision，但最终使用 `completeAll()` 之前的旧读数落 stage ledger。若两次读取之间计划准入/切换，正文可能属于 N+1，账本却记录 N。
2. `FinalAuditService` 只捕获 `requireActive()` 的异常，紧随其后的 `loadCompiled()` 抛出的快照、契约、数据库或空指针异常会逃逸，编排器把它记录成通用 `STAGE_HANDLER_FAILED`，而不是终检契约要求的 `FINAL_AUDIT_BLOCKER`。

本计划只修复这两个阻断问题，并补齐可证明修复成立的失败测试。不得借此进入阶段 5。

## 1. 现状与失败证据

### 1.1 DRAFT 调用链

```text
DeriveRunOrchestrator.advance
  -> DraftStageHandler.execute
       -> snapshotService.requireActive(runId)             // 预读 revision N
       -> FullDraftCompletionService.completeAll(runId)
            -> snapshotService.requireActive(runId)        // 可能已经是 N+1
            -> snapshotService.loadCompiled(runId, N+1)
            -> 逐章写正文
            -> snapshotService.requireActive(runId, N+1)
       -> StageOutcome.done(active.getRevision(), ...)     // 仍可能写 N
  -> StageRunStateService.done(... outputRevision ...)
```

关键位置：

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java:32`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java:46`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FullDraftCompletionService.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanSnapshotService.java`

### 1.2 FINAL_AUDIT 调用链

```text
DeriveRunOrchestrator.advance
  -> FinalAuditStageHandler.execute
       -> FinalAuditService.audit(runId)
            -> snapshotService.requireActive(runId)
            -> snapshotService.loadCompiled(...)           // 当前未被 try/catch 包住
       -> 未捕获异常被 DeriveRunOrchestrator 捕获
       -> STAGE_HANDLER_FAILED
```

关键位置：

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditService.java:53-60`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandler.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DeriveRunOrchestrator.java`

### 1.3 上一轮修复为什么仍然被拒绝

上一轮 F1-F3 已修复：

- BLOCKER 报告返回 `FinalQuality.FAILED`；
- `missingChapters` 非空时禁止 DRAFT DONE；
- `SceneDraftException` 之外的正文异常不再包装成 `DRAFT_CHAPTER_EMPTY`。

但没有收口以下边界：

- handler 预读与 `completeAll()` 返回值之间没有 revision 所有权；
- `loadCompiled()` 不在终检失败分类范围内；
- 全量测试通过不能证明“两次读取之间发生计划切换”这种并发时序；
- 测试没有证明错误从 service 到 stage ledger 的完整分类链路。

因此这是修复计划遗漏和测试覆盖不足，不是阶段 4 的总体算法方向错误。

## 2. 修复目标与不变量

修复后必须同时满足：

1. DRAFT 成功落账的 `outputRevision` 必须来自本次 `completeAll()` 返回的实际 revision，不能来自 handler 的旧预读。
2. `missingChapters` 非空仍返回 `DRAFT_INCOMPLETE`，不进入 `FINAL_AUDIT`。
3. `requireActive()`、`loadCompiled()` 的 active revision/plan graph 读取失败都必须返回 `FinalQuality.FAILED` 且包含 `FINAL_AUDIT_BLOCKER`。
4. `loadCompiled()` 的 `BizException`、数据库运行时异常、NPE 或契约校验异常不得逃逸为编排器的 `STAGE_HANDLER_FAILED`。
5. 终检仍然是只读：不得修改 `StoryContract`、`CompiledStoryPlan`、`PlanSnapshot`、`run.active_plan_revision` 或章节正文。
6. 不得新增迁移、不得修改主站 `novelgen`、不得部署、不得 commit/push、不得进入阶段 5。

## 3. Global Constraints

- 先读并遵守：
  - `最高约束.md`
  - `AGENTS.md`
  - `docs/code-standards.md`
  - `docs/STATUS.md`
  - `docs/architecture/pipeline-contracts.md`
  - `docs/superpowers/plans/2026-10-30-derive-stage4-remediation-plan.md`
- 只允许修改本计划列出的生产文件、测试文件和 `docs/STATUS.md`。
- 所有测试先实现为红，保存实现前日志到 `var/stage4-v2/test-before.log`。
- 不使用 `mvn clean`；管线运行中不重启后端。
- 不使用 `git reset --hard`、`git checkout --`、删除用户已有改动。
- 测试必须是真实失败证据，不允许只提交“文件时间较早”或“测试已通过”作为 TDD 证明。

## 4. Task R1：DRAFT revision 落账所有权

### 4.1 Boundary

只修改：

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandlerTest.java`

不得修改 `FullDraftCompletionService`、`PlanSnapshotService`、V140、数据库或编排器。

### 4.2 Why

当前 handler 的 `active` 是旧读数，而 `completeAll()` 返回的 `DraftCompletionResult.planRevision()` 才是本次正文实际使用的 revision。若两者之间计划发生准入/切换，旧读数落账会让 stage ledger 与正文产物不一致。

### 4.3 Inputs

- `StageClaim.stageRunId()`
- `FullDraftCompletionService.completeAll(long runId)`
- `FullDraftCompletionService.DraftCompletionResult.planRevision()`
- `DraftCompletionResult.missingChapters`
- `DraftCompletionResult.chaptersDegraded`

### 4.4 Outputs

- DRAFT handler 不再在成功路径调用 `snapshotService.requireActive()` 获取落账 revision。
- 成功结果使用：

```java
StageOutcome.done(
    result.planRevision(),
    result.chaptersDegraded(),
    serializedResult
)
```

- `missingChapters` 非空时仍返回：

```text
code    = DRAFT_INCOMPLETE
path    = chapter[<第一个缺失章号>]
message = 包含完整缺失列表
```

- 建议移除 `PlanSnapshotService` 依赖及其构造参数；若保留依赖，必须证明成功路径没有再次读取 active revision，且不得用旧预读覆盖 result revision。

### 4.5 Required Tests

`DraftStageHandlerTest` 必须新增或改写为：

```java
void completedDraftStagePersistsCompletedRevision()
void completedDraftStagePersistsRevisionReturnedByCompletionService()
void completeAllWithMissingChaptersReturnsFailedStageOutcome()
void failedDraftStageDoesNotAdvanceToFinalAudit()
```

关键测试模拟：

```text
completionService.completeAll(59L)
  -> DraftCompletionResult(planRevision = 6, ...)
```

如果实现仍保留 handler 预读，则可让旧预读返回 5、完成结果返回 6，并断言最终 `outputRevision == 6`。更推荐删除该预读，使旧实现无法继续依赖。

### 4.6 Pass Criteria

- `completedDraftStagePersistsRevisionReturnedByCompletionService` 在当前代码上先失败；
- 修复后 DRAFT DONE 的 revision 恒等于 `result.planRevision()`；
- 缺章路径仍为 `DRAFT_INCOMPLETE`，不写 DONE；
- 未修改正文生成算法；
- 未修改快照、active pointer、V140 或数据库。
## 5. Task R2：FINAL_AUDIT 快照加载失败分类

### 5.1 Boundary

只修改：

- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditService.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FinalAuditServiceTest.java`
- `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandlerTest.java`

不得修改 `FinalAuditStageHandler` 的正常 PASS/ADVISORY/DEGRADED 分支；不得修改计划编译器、快照准入或正文写入路径。

### 5.2 Why

当前代码只把 `snapshotService.requireActive(runId)` 放进 try/catch，`loadCompiled()` 位于 catch 之外：

```java
try {
    active = snapshotService.requireActive(runId);
} catch (RuntimeException e) {
    return FAILED + FINAL_AUDIT_BLOCKER;
}
CompiledStoryPlan plan = snapshotService.loadCompiled(runId, active.getRevision());
```

当 snapshot 不存在、graph/hash 不一致、JSON 反序列化 NPE、数据库异常或契约校验失败时，异常继续上抛，编排器最终写入 `STAGE_HANDLER_FAILED`，不符合终检契约。

### 5.3 Inputs

- `snapshotService.requireActive(runId)`
- `snapshotService.loadCompiled(runId, active.getRevision())`
- `BizException`
- `DataAccessResourceFailureException` 等数据库运行时异常
- `NullPointerException`
- `FinalAuditReport.FinalQuality`
- `PlanAuditIssue.Severity.BLOCKER`

### 5.4 Outputs

把 active revision 解析和 compiled plan 加载放进同一失败分类边界。推荐实现形状：

```java
DerivePlanSnapshotDO active = null;
CompiledStoryPlan plan;
try {
    active = snapshotService.requireActive(runId);
    plan = snapshotService.loadCompiled(runId, active.getRevision());
} catch (RuntimeException e) {
    int revision = active == null ? 0 : active.getRevision();
    return new FinalAuditReport(
        runId,
        revision,
        FinalAuditReport.FinalQuality.FAILED,
        List.of(blocker(
            "FINAL_AUDIT_BLOCKER",
            "run[" + runId + "]",
            finalAuditFailureMessage(e),
            finalAuditFailureActual(e)
        ))
    );
}
```

要求：

- issue code 保持 `FINAL_AUDIT_BLOCKER`，不能因为底层异常类型而改成空报告或 PASS；
- message 必须能区分 active revision 不可用与 plan graph 加载失败；
- `actual` 至少保留异常类型，必要时保留安全的 message；禁止打印堆栈或敏感配置；
- `BizException`、数据库 RuntimeException、NPE 都必须变成 `FAILED`；
- 不要捕获 `Error`；
- 只转换快照解析/compiled plan 加载边界，正文查询、章节审计逻辑不应被静默吞掉；
- `runMapper` 返回空时仍返回现有 `run 未关联正文目标书` blocker。

### 5.5 Required Tests

`FinalAuditServiceTest` 必须增加：

```java
void loadCompiledBizExceptionProducesFailedQualityAndBlocker()
void loadCompiledDatabaseExceptionProducesFailedQualityAndBlocker()
void loadCompiledNullPointerExceptionProducesFailedQualityAndBlocker()
void loadCompiledFailureDoesNotEscapeToStageHandler()
```

每个测试必须断言：

- `quality == FinalQuality.FAILED`；
- 至少有一个 `code == FINAL_AUDIT_BLOCKER` issue；
- issue path 为 `run[59]`；
- 没有调用 `runMapper.updateStatus` 或章节写入方法。

`FinalAuditStageHandlerTest` 至少增加一条真实 service 路径测试，或等价的 handler 集成测试，证明 `loadCompiled()` 失败后 stage outcome 的 error code 是 `FINAL_AUDIT_BLOCKER`，而不是 `STAGE_HANDLER_FAILED`。

### 5.6 Pass Criteria

- `requireActive()` 失败仍返回 `FAILED`；
- `loadCompiled()` 抛 `BizException`、数据库 RuntimeException、NPE 时都返回 `FAILED`；
- FinalAuditStageHandler 看到 BLOCKER 时返回 `StageOutcome.failed("FINAL_AUDIT_BLOCKER", ...)`；
- 缺章、空章、meta leak、revision drift 的既有测试继续通过；
- 不修改计划、正文或 active pointer。

## 6. Task R3：回归验证与证据留档

### 6.1 Boundary

只运行已有测试、补充必要断言并更新状态文档；不得新增业务实现。

### 6.2 Required Files

- `docs/STATUS.md`
- `var/stage4-v2/test-before.log`
- `var/stage4-v2/test-after-r1.log`
- `var/stage4-v2/test-after-r2.log`
- `var/stage4-v2/test-full.log`
- `var/stage4-v2/review-notes.md`（可选，供 Reviewer 附证据）

### 6.3 Required Commands

PowerShell 中执行，必须保存 stdout、stderr 和退出码：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DraftStageHandlerTest,FullDraftCompletionServiceTest,FinalAuditServiceTest,FinalAuditStageHandlerTest,DeriveRunOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

然后：

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
```

### 6.4 Pass Criteria

- 实现前失败日志确实包含新测试失败或编译失败；
- 实现后定向测试全部通过；
- 全量测试 0 failures、0 errors；
- 日志中明确记录命令、退出码和测试总数；
- `git diff --check` 通过；
- 检查修改文件列表，未越过 Boundary；
- 未执行 commit、push、部署、改库、重启；
- 未进入阶段 5。

## 7. Definition of Done

- [ ] DRAFT 成功结果使用 `result.planRevision()`；
- [ ] handler 不再用旧预读覆盖完成服务返回的 revision；
- [ ] missing chapter 仍阻止 DRAFT DONE；
- [ ] active revision 与 loadCompiled 失败都返回 `FAILED`；
- [ ] loadCompiled 的 BizException/DB/NPE 不再逃逸成 `STAGE_HANDLER_FAILED`；
- [ ] 缺章、空章、meta leak、revision drift、重复段落既有语义未回归；
- [ ] 定向测试与全量测试通过；
- [ ] 实现前红测试日志与实现后日志均已留档；
- [ ] 未进入阶段 5；
- [ ] 未 commit/push/deploy。

## 8. Reviewer 判定标准

Reviewer 必须逐项核验代码与测试，而不是只看“测试通过”：

1. `DraftStageHandler` 成功落账是否直接使用 `result.planRevision()`；
2. 是否存在任何成功路径用旧 `active.getRevision()` 覆盖 result revision；
3. `FinalAuditService` 是否把 `requireActive()` 与 `loadCompiled()` 纳入同一失败分类；
4. `loadCompiled()` 的 BizException、数据库异常、NPE 是否都变成 `FAILED + FINAL_AUDIT_BLOCKER`；
5. `FinalAuditStageHandler` 是否不会把上述失败写成 `STAGE_HANDLER_FAILED`；
6. 是否新增了真实的 revision 切换测试，而不是只测试 revision 相同的 happy path；
7. 是否新增了真实的 loadCompiled 失败测试，而不是只测 `requireActive()`；
8. 是否修改了 Boundary 之外的文件；
9. 是否进入阶段 5、部署、改数据库或 commit/push。

任何一项不满足，Decision 必须是 `REJECT`，并给出 absolute path:line、缺失测试和可执行修复动作。
