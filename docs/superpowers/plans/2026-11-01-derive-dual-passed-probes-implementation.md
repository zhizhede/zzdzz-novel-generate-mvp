# 随机衍生双 PASSED 短篇探针 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先让一本悬疑短篇和一本言情短篇分别从真实建 run 一直跑到 `run.status=PASSED`，再继续做更多类型和长篇扩展。

**Architecture:** 保留当前 `SAMPLING → PLANNING → DRAFTING → P6 → ⑦ → ⑧` 主链；优先修复合同版本、闭合窗口、字数预算、direct 语义和现有 V2 测试失败；两个真实探针必须使用 `direct=false`，并通过合同、义务、证据、P6、QC、正文的完整链路。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Flyway、JUnit 5、Mockito。

**Specs:**

```text
docs/superpowers/specs/2026-11-01-derive-dual-passed-probes-design.md
docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design-v2.md
```

**Current baseline:**

```text
宝塔 derive schema = 146
run64 = FAILED：P6 已 PASS，但总字数 7329 超出 3000~5000
run65 = PLANNING：第5章 FINAL_CLOSURE 校验失败，无正文、无 P6、无 QC
run66 = PLANNING：V143/V144/V145/V146 已写入，但第5章闭合窗口失败
当前全量测试存在 1 个失败：V145 唯一约束测试与迁移实现不一致
```

## Global Constraints

- 先读两份 V2 设计、`README-handoff.md`、`最高约束.md`、`AGENTS.md`、`docs/code-standards.md` 和当前相关 Java/XML/SQL。
- 第一目标不是扩展新类型，而是跑通 `MYSTERY` 和 `ROMANCE` 两个真实 `PASSED` 探针。
- 两个探针必须使用 `direct=false`；`direct=true` 只能作为额外草稿测试，不能作为 PASSED 验收。
- 不得删除旧 `StoryContract`、旧 ROUTE parser、旧 P6/QC 或旧 run 读路径。
- 不得把 `RomanceContract` 强转为 `StoryContract`。
- 不得用 `text.contains(targetKey)` 判断义务兑现。
- 不得让 P6/QC 构造空 `StoryKernel`。
- 不得用 `Map.of()` 丢弃义务 payload。
- 未经用户明确授权，禁止 commit、push、部署。
- 每个任务先写失败测试，再实现，再运行相关测试；最后运行全量测试和打包。
- Flyway 只能新增迁移，不得修改历史迁移。

## Review Focus

以下失败模式必须由任务测试覆盖：

1. **合同版本不一致**：run66 出现 `StoryContract.version=1`、`GenreContract.contract_version=0`、`SCHEDULE_STALE`；必须统一版本。
2. **闭合窗口失败**：run65/run66 第5章缺 `FINAL_CLOSURE`；必须区分当前窗口、未来窗口和最终闭合。
3. **字数超限过晚发现**：run64 P6 已 PASS，但 QC 因 7329/5000 失败；必须在窗口阶段拦截。
4. **direct 绕过确定性审计**：direct=true 不得跳过闭合、义务、预算和脚本 QC。
5. **V2 遗留测试失败**：当前 V145 迁移使用 unique index，测试仍断言 table-level UNIQUE；必须同步断言，不得降低数据库约束。

---

## Task 1: 修复当前全量测试失败

**Files:**

- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryObligationEvidencePersistenceTest.java`
- Modify: `novelgen-data/src/main/resources/db/migration-derive/V145__derive_story_obligation_evidence.sql` only if a real SQL defect is found

**Interfaces:**

- Consumes: 当前 V145 迁移和 XML。
- Produces: 全量测试通过；数据库仍保留 nullable `event_key`/`beat_seq` 的复合唯一性。

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
evidenceMigrationUsesUniqueIndexWithCoalesce
evidenceMigrationKeepsCascade
evidenceMigrationKeepsRunChapterIndex
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationEvidencePersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 当前断言与实现不一致时失败。

- [ ] **Step 3: 修复断言或 SQL**

当前实现使用：

```sql
CREATE UNIQUE INDEX ...
ON derive_story_obligation_evidence(
  run_id, obligation_key, evidence_kind, chapter_no,
  COALESCE(event_key, ''), COALESCE(beat_seq, -1)
);
```

测试必须断言这一实现，不得要求 PostgreSQL 在表级 `UNIQUE` 中使用表达式。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationEvidencePersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 运行全量测试**

Run: `mvn -q test`

Expected: 0 failures

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 2: 修复合同版本一致性

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/ContractVersionService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/ContractVersionConsistencyTest.java`

**Interfaces:**

- Consumes: `RunDO`、`derive_genre_contract`、`derive_story_contract`、`derive_chapter_schedule`。
- Produces:

```text
ContractVersionSnapshot(
    int storyContractVersion,
    int genreContractVersion,
    int scheduleVersion)

ContractVersionService.current(RunDO run) -> ContractVersionSnapshot
ContractVersionService.assertConsistent(RunDO run) -> void
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
newRunVersionsMatchAfterContractFreeze
scheduleVersionMatchesGenreContract
legacyRunWithoutGenreContractStillLoads
staleScheduleIsDetected
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=ContractVersionConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 当前 run66 类型版本不一致时失败。

- [ ] **Step 3: 统一版本来源**

冻结合同后：

```text
storyContract.version
derive_genre_contract.contract_version
derive_chapter_schedule.contract_version
```

必须使用同一个版本号；不能在 codec 中默认写 `0`。

`DeriveStoryPlanService` 的 `SCHEDULE_STALE` 检查必须读取统一版本快照，而不是分别读取旧字段后猜测。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=ContractVersionConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 3: 闭合合同和窗口作用域

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryClosureContract.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/WindowObligationScope.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidator.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/DualProbeClosureWindowTest.java`

**Interfaces:**

- Consumes: `StoryObligation`、`StoryClosureContract`、BATCH 输出。
- Produces:

```text
WindowObligationScope.of(
    int windowFrom,
    int windowTo,
    List<StoryObligation> obligations)
    -> WindowObligationScope

ClosureWindowValidator.validate(
    DraftWindow window,
    StoryClosureContract contract)
    -> List<PlanIssue>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
futureClosureIsDeferred
currentClosureRequiresActs
currentClosureRequiresTags
currentClosureRequiresProofEvents
pastUnfulfilledClosureFails
validClosureAccepts
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DualProbeClosureWindowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现窗口作用域**

规则：

```text
义务章号 > windowTo → DEFERRED
义务章号在窗口内 → current
义务章号 < windowFrom → past，若未完成则失败
```

第5章义务不得在第4章窗口报 `OBLIGATION_MISSING`。

- [ ] **Step 4: 实现结构化闭合校验**

悬疑揭示章必须有：

```text
closingActs = CULPRIT/MOTIVE/METHOD
closureEvidence.culpritKey
closureEvidence.motiveTag
closureEvidence.methodTag
closureEvidence.proofEventKeys
```

言情结局章必须有：

```text
closingActs = RELATIONSHIP_DECISION
closureEvidence.relationshipThreadKey
closureEvidence.proofEventKeys
```

不要求正文逐字包含合同标签。

- [ ] **Step 5: 修改 DeriveDraftService**

闭合失败必须返回：

```text
errorCode
windowNo
chapterNo
obligationKey
missingActs
contractPayload
repairTarget=WINDOW[n]
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DualProbeClosureWindowTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 7: 记录提交状态**

未获用户授权不 commit。

---

## Task 4: 字数预算窗口护栏

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/WordBudgetGuard.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/DualProbeBudgetGuardTest.java`

**Interfaces:**

- Consumes: run 的 `wordsMin/wordsMax`、窗口字数、剩余章数、章均预算。
- Produces:

```text
BudgetDecision(
    String code,
    String repairTarget,
    int projectedMin,
    int projectedMax)

WordBudgetGuard.check(
    int totalCharCount,
    int windowCharCount,
    int remainingChapters,
    int chapterAvg,
    int wordsMin,
    int wordsMax)
    -> BudgetDecision
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
currentWindowOverBudgetRejects
projectedOverBudgetRejects
projectedUnderBudgetRejects
validBudgetAccepts
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DualProbeBudgetGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现预算护栏**

每个 BATCH 窗口落库前检查：

```text
totalCharCount > wordsMax
projectedMax > wordsMax
projectedMax < wordsMin
```

错误：

```text
BUDGET_WINDOW_OVERSHOOT
BUDGET_PROJECTED_OVERSHOOT
BUDGET_WINDOW_UNDERSHOOT
```

回炉：

```text
只重做当前窗或压缩剩余窗
不得默认清空整本书
```

- [ ] **Step 4: 接入 DeriveDraftService**

预算失败必须在正文生成前阻止当前窗口继续落库。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DualProbeBudgetGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 5: direct 模式只跳过 LLM 审稿

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DirectProbeModeTest.java`

**Interfaces:**

- Consumes: 义务校验、闭合校验、预算护栏、脚本 QC。
- Produces: `AutoVO` API 不变，但 direct 模式仍执行确定性审计。

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
directStillRunsObligationWindow
directStillRunsClosureWindow
directStillRunsBudgetGuard
directStillRunsScriptQc
directSkipsOnlyLlmSemanticReview
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DirectProbeModeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 修改 direct 分支**

`direct=true` 只允许跳过：

```text
LLM P6 语义审稿
LLM QC 五项审稿
```

不得跳过：

```text
义务窗口
闭合窗口
字数预算
脚本 QC
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DirectProbeModeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获用户授权不 commit。

---

## Task 6: 悬疑短篇真实 PASSED 探针

**Files:**

- Create: `var/derive-probe-mystery-passed.json`
- Create: `var/derive-probe-mystery-passed-report.md`

**Interfaces:**

- Consumes: 宝塔 derive API、登录 cookie、V143～V146 数据库。
- Produces: 悬疑真实 run 的完整验收报告。

**Implementation:**

- [ ] **Step 1: 固定参数**

```json
{"genre":"MYSTERY","wordsMin":3000,"wordsMax":5000,"tags":["悬疑"],"pace":"0.75","seed":450067,"lenCheck":true,"antiRepro":false}
```

- [ ] **Step 2: 登录、建 run、启动 auto**

必须使用：

```text
direct=false
Origin: https://zzdzz.cn:8888
```

- [ ] **Step 3: 轮询并记录证据**

必须记录：

```text
合同版本一致性
义务数量
证据数量
每个窗口校验
P6 verdict
每章正文非空
总字数
QC verdict
run.status
```

- [ ] **Step 4: 验收**

必须满足：

```text
5章正文非空
总字数3000~5000
P6 PASS
QC PASS
run.status=PASSED
```

- [ ] **Step 5: 写报告**

报告写入 `var/derive-probe-mystery-passed-report.md`，不得包含 API key 或口令。

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 7: 言情短篇真实 PASSED 探针

**Files:**

- Create: `var/derive-probe-romance-passed.json`
- Create: `var/derive-probe-romance-passed-report.md`

**Interfaces:**

- Consumes: `RomanceProfile`、`RomanceCodec`、宝塔 derive API、登录 cookie。
- Produces: 言情真实 run 的完整验收报告。

**Implementation:**

- [ ] **Step 1: 固定参数**

```json
{"genre":"ROMANCE","wordsMin":3000,"wordsMax":5000,"tags":["言情"],"pace":"0.50","seed":450068,"lenCheck":true,"antiRepro":false}
```

- [ ] **Step 2: 登录、建 run、启动 auto**

必须使用：

```text
direct=false
Origin: https://zzdzz.cn:8888
```

- [ ] **Step 3: 轮询并记录证据**

必须记录：

```text
RomanceContract 已冻结
关系线 REL-C1-C2 已创建
阶段义务数量
romanceEvents 证据
P6 verdict
每章正文非空
总字数
QC verdict
run.status
```

- [ ] **Step 4: 验收**

必须满足：

```text
7章正文非空
总字数3000~5000
P6 PASS
QC PASS
run.status=PASSED
合同中没有 culprit/motive/method/revealChapter/clue
```

- [ ] **Step 5: 写报告**

报告写入 `var/derive-probe-romance-passed-report.md`。

- [ ] **Step 6: 记录提交状态**

未获用户授权不 commit。

---

## Task 8: 双 PASSED 后的业务回归

**Files:**

- Modify: `docs/STATUS.md`
- Create: `var/derive-dual-passed-summary.md`

**Interfaces:**

- Consumes: Task 6/7 的真实报告。
- Produces: 双 PASSED 的业务状态记录。

**Implementation:**

- [ ] **Step 1: 汇总两个 run**

报告必须列出：

```text
runId
genre
章数
总字数
义务数量
证据数量
P6
QC
最终状态
失败重试次数
```

- [ ] **Step 2: 更新 STATUS**

必须明确：

```text
悬疑短篇 PASSED
言情短篇 PASSED
是否还有遗留问题
是否允许继续做其他类型
```

- [ ] **Step 3: 运行全量验收**

Run:

```text
mvn -q test
mvn -q -DskipTests package
git diff --check
```

- [ ] **Step 4: 记录提交状态**

未获用户授权不 commit。

---

## Final Verification

全部任务完成后必须证明：

- 当前全量测试失败已修复；
- 合同版本一致，不再出现 `SCHEDULE_STALE`；
- 第5章闭合不再在第4章窗口提前失败；
- run64 类型的字数超限在窗口阶段被拦截；
- direct=true 不再绕过义务、闭合、预算和脚本 QC；
- 悬疑真实 run `PASSED`；
- 言情真实 run `PASSED`；
- 两本正文完整且总字数在范围内；
- 义务 payload 和证据真实落库；
- P6/QC 使用完整 StoryKernel 和证据索引；
- 没有未经授权的 commit、push、部署。
