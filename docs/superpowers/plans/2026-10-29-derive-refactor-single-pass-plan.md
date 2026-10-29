# DeriveFlow 一次性重构执行计划

> 本文件替代 2026-10-28 的 33-Task 拆分。执行者按阶段工作，不按旧 Task 编号工作。

**Goal:** 将随机衍生链路收敛成一条可稳定执行的管线：节点输出协议先闭合，再生成不可变计划快照，再按章逐场生成正文；正文阶段不再改计划。

**Architecture:** 先统一 LLM wire contract，再用 Java 确定性编译和审计计划，最后由 SceneWriter/ChapterAssembler 完成正文。旧 PLAN_V2/ROUTE 路径保留为只读兼容层，不再作为新 run 主路径。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Jackson、JUnit 5、Vue 3。

**Spec:** 本文件；实施前仍必须读取 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`。

## Global Constraints

- 不改主站 `novelgen`。
- 未获用户当轮允许，禁止 commit、push、部署、改线上库、重启线上服务。
- 管线运行中禁止 `mvn clean`、禁止重启后端。
- 新迁移只允许从 `V139` 开始；不改历史迁移。
- `StoryContract` 只允许写故事事实；`PlanSnapshot` 只允许写计划；`ChapterWriter` 只允许写正文。
- `run.step_status` 只作为兼容投影，不再作为新路径业务事实源。
- 模型不生成 `beatRole`、`chapterNo`、`seq`、`beatKey`、`threadKey`、`clueKey` 或跨层稳定 ID。
- 所有 JSON 输出必须有 schema、枚举白名单、字段路径错误和历史回放测试。
- 正文阶段不得修改 `StoryContract`、`PlanSnapshot` 或 `active_plan_revision`。
- 任何 Task 若遇到计划外的数据库约束、模块依赖或业务语义冲突，停止并报告，不自行扩大范围。

## 阶段顺序

```text
阶段1 输出协议
阶段2 计划快照
阶段3 计划编译与准入
阶段4 正文闭环
阶段5 全量验收
阶段6 授权后部署
```

每个阶段只有一个 Worker，完成后交给独立 Reviewer；Review 返回 `APPROVE` 才进入下一阶段。

---

## 阶段1：输出协议闭合

### Boundary

只实现节点协议、ROUTE v2 wire contract、PromptCatalog 模板和状态读模型；不实现计划编译器、正文 writer 或数据库迁移。

### Why

先把“哪些字段允许模型输出、哪些字段由 Java 生成”固定，否则后续每个任务都会重新制造同类格式问题。

### Inputs

- `A1` 历史 replay fixture；
- `PlanContracts`；
- `JsonSchemaSupport`；
- `LlmJson`；
- `PromptCatalog`；
- ROUTE v2 正负样例。

### Outputs

- `ContractViolation`
- `NodeOutputContract`
- `JsonSchemaSupport`
- `RouteNarrative`
- `RouteNarrativeContract`
- `PlanContractSchemas`
- ROUTE system prompt
- ROUTE schema fragment
- 有效调用状态读模型

### Required files

- `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/ContractViolation.java`
- `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/NodeOutputContract.java`
- `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/JsonSchemaSupport.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContractSchemas.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrative.java`
- `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/RouteNarrativeContract.java`
- `novelgen-model/src/main/java/com/zzdzz/novelgen/model/enums/LlmCallEffectiveStatusEnum.java`
- `web/src/views/derive/DeriveRunDetailView.vue`

### Interface contract

```java
public record RouteNarrative(
        List<BeatNarrative> beatNarratives,
        List<SkippedBeat> skipped) {}

public static NodeOutputContract<RouteNarrative> routeNarrative();

public enum LlmCallEffectiveStatusEnum {
    ACCEPTED, CONTENT_REJECTED, CALL_FAILED, INTERRUPTED
}
```

### Pass Criteria

- 历史 ROUTE 错误可回放并得到稳定错误码；
- ROUTE v2 不允许输出 `beatRole/chapterNo/seq`；
- `MIDPOINT/OBSTACLE/COMPLICATION` 等非法值被 schema 拒绝；
- 重复 `C1` 被明确分类，不误报成 `seq/filled`；
- 所有枚举从 Java 定义生成；
- `status=ok + error_msg` 在 UI 显示为“内容拒绝”，不是“成功”；
- 定向测试先红后绿；
- 旧 ROUTE 路径不被删除，但不再是新路径主入口。

---

## 阶段2：计划快照

### Boundary

只实现不可变计划快照和 `plan_revision` 持久化；不实现编译算法、P6 或正文生成。

### Why

当前章节、拍、卷、线程、日程分散在多表中，后续阶段会互相读到不同版本；快照是唯一计划事实源。

### Inputs

- 阶段1 的 `RouteNarrative`；
- `StoryContract`；
- `ScaffoldContract`；
- `ChapterScheduleEntry`；
- 当前 `chapters`、`beat_instance` 表结构。

### Outputs

- `V139__derive_plan_snapshot.sql`
- `run.active_plan_revision`
- `chapters.plan_revision`
- `beat_instance.plan_revision`
- `DerivePlanSnapshotDO`
- `DerivePlanSnapshotMapper`
- `CompiledStoryPlan`
- `PlanGraphRecords`
- `PlanSnapshotService`

### Interface contract

```java
record CompiledStoryPlan(
        long runId,
        int contractVersion,
        String contractHash,
        PlanContracts.ScopeContract scope,
        List<ChapterScheduleEntry> schedule,
        List<PlanContracts.VolumeContract> volumes,
        List<PlanContracts.CharacterContract> characters,
        List<PlannedChapter> chapters,
        List<PlannedBeat> beats) {}

DerivePlanSnapshotDO persistDraft(long runId, CompiledStoryPlan plan);
DerivePlanSnapshotDO requireActive(long runId);
void admit(long runId, int revision, String reportJson, String planHash);
```

### Pass Criteria

- V139 可在测试库重复执行；
- `run.active_plan_revision` 唯一指向 ADMITTED snapshot；
- 章节和拍都携带 `plan_revision`；
- revision 不一致时返回 `PLAN_REVISION_STALE`；
- JSONB 显式使用 mapper XML；
- 快照 JSON 可稳定序列化和反序列化；
- 定向测试先红后绿。

---

## 阶段3：计划编译与准入

### Boundary

只把合同、日程、候选拍编译成计划并做确定性审计；不生成正文。

### Why

run59/60 的结构问题都发生在计划阶段，P6 只能事后发现。计划必须在写正文前通过 Java 确定性校验。

### Inputs

- 阶段2 的 `CompiledStoryPlan`；
- `StoryContract`；
- `ScaffoldContract`；
- `BeatCandidate`；
- `ChapterScheduleEntry`；
- `PlanContracts` 枚举和错误码。

### Outputs

- `BeatAssignmentSolver`
- `ContinuitySimulator`
- `StoryPlanCompiler`
- `PlanAuditor`
- `PlanAdmissionService`
- `PlanProjectionService`
- `/gate` 兼容适配器

### Interface contract

```java
PlanCompileResult compile(PlanCompileInput input);
PlanAuditReport audit(CompiledStoryPlan plan, StoryContract contract);
PlanAdmissionResult admit(long runId, int revision);
```

### Deterministic rules

- 每个 clue 的 plant/payoff 只能来自合同；
- reveal 只能发生在合同章；
- 每个 beat 必须恰好 assigned 或 skipped 一次；
- `dependsOn` 必须只指向更早的最终 seq；
- knowledge 读取必须早于写入来源；
- thread 必须 open→echo→payoff/close；
- reveal 章必须具备合同要求的 motive/reveal/payoff；
- 所有结构错误必须返回 `PlanIssue`，不能直接抛 P6 文案；
- audit 无 BLOCKER 才允许 admission；
- semantic review 只产生 ADVISORY，不得改变计划。

### Pass Criteria

- 12 章和 15 章 fixture 都生成完整计划；
- 每条 clue 恰好一次 plant/payoff；
- 计划 hash 稳定；
- run59 的 5 类问题在 admission 前被定位；
- `/gate` 能映射 ADMIT/REPAIR/REJECT；
- 正文尚不存在时可激活投影；
- 定向测试先红后绿。

---

## 阶段4：正文闭环

### Boundary

只从 ADMITTED snapshot 生成正文；不改计划、不重排 beat、不回 SCALE/SPINE。

### Why

正文是长流程，单章/单场失败必须局部修复。当前“整章+机器 JSON 尾巴”的写法会重新制造结构错误并阻断后续章节。

### Inputs

- `PlanSnapshotService.requireActive`；
- `ChapterScript`；
- `SceneScript`；
- `SceneWriter`；
- `ChapterAssembler`；
- 现有 `chapters`/`beat_instance` mapper。

### Outputs

- `V140__derive_stage_run.sql`
- `DeriveStageRunDO`
- `DeriveStageRunMapper`
- `DeriveRunOrchestrator`
- `SceneWriter`
- `ChapterAssembler`
- `ChapterDraftCompletionService`
- `FullDraftCompletionService`
- `FinalAuditService`

### Interface contract

```java
SceneDraft write(SceneScript script, SceneContext context);
ChapterDraftResult completeChapter(long runId, int chapterNo);
DraftCompletionResult completeAll(long runId);
FinalAuditReport audit(long runId);
AutoVO advance(long runId);
AutoVO cancel(long runId);
AutoVO status(long runId);
```

### Deterministic rules

- scene 正文只输出散文，不输出机器 JSON；
- 每个 scene 只重试自己；
- 每个章节失败只重试本章；
- 所有章节完成后才进入 FINAL_AUDIT；
- 正文写入必须绑定 `plan_revision`；
- stage state、attempt、lease、错误 code/path 全部落库；
- 服务重启后从最后完成 stage 恢复；
- 正文阶段不得修改计划。

### Pass Criteria

- 12 章 fixture 全部非空；
- 某章失败不影响后续章；
- 某 scene 失败不影响后续 scene；
- plan revision 在 DRAFT 阶段不变；
- 服务重启可恢复；
- 最终审计不修改计划；
- 定向测试先红后绿。

---

## 阶段5：全量验收

### Boundary

只运行测试、故障注入和 fresh-run 验收；不新增业务功能。

### Inputs

- 阶段1–4；
- 10 个 fresh-run fixture；
- 历史回放 fixture；
- 当前 jar 构建产物。

### Outputs

- 全量 `mvn test` 结果；
- derive jar 构建结果；
- 10 个 fresh-run 验收报告；
- `docs/STATUS.md` 追加记录。

### Acceptance matrix

必须覆盖：

- 单卷悬疑；
- 非推理单卷；
- 多卷；
- 有样本/无样本；
- 不同 seed；
- 12–18K 字；
- 8–15 章；
- reveal 合同；
- 同章多 payoff；
- unknown enum；
- duplicate state key；
- 空章节；
- 重启恢复；
- 正文 cancel；
- stale plan revision。

### Pass Criteria

- 所有计划 schema violations = 0；
- 所有计划 hard issues = 0；
- 所有 clue plant/payoff 正确；
- 所有章节正文非空；
- plan revision 不变；
- run 到达 `DRAFT_COMPLETE`；
- 终审无 BLOCKER；
- `mvn test`、jar 构建、前端构建全部通过。

---

## 阶段6：授权后部署

### Boundary

只有用户当轮明确授权后才执行；没有授权就只提交验收报告，不部署。

### Inputs

- 阶段5 全部通过；
- 用户明确部署授权；
- 活动 run 检查；
- 备份策略。

### Outputs

- 旧 jar SHA256；
- 备份路径；
- 新 jar SHA256；
- 服务状态；
- Flyway 状态；
- 新 run 实跑记录。

### Pass Criteria

- 未授权时绝不部署；
- 授权后只部署 derive app/必要前端；
- 备份可回滚；
- 新 run 不复用 run58/59/60；
- 全程不改主站；
- 全程不改历史迁移；
- 部署失败立即停止。

---

## Definition of Done

- [ ] 旧 33-Task 执行文档不再作为主执行入口；
- [ ] 阶段1–4 的 Worker/Reviewer 都返回 APPROVE；
- [ ] 新 run schema violations = 0；
- [ ] 新 run 计划 hard issues = 0；
- [ ] 所有章节正文非空；
- [ ] plan revision 在正文阶段不变；
- [ ] 全量测试和构建通过；
- [ ] 未获授权时没有 commit/push/deploy。
