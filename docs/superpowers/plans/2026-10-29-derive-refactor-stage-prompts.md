# DeriveFlow 单次重构阶段 Prompt

> 这是新的主执行入口。每个阶段只有一个 Worker 和一个 Reviewer，不再使用 33 个 Task。

## 通用规则

工作目录：

```text
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp
```

所有执行者和审查者必须先读取：

```text
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md
```

执行顺序：

```text
1. 输出协议闭合
2. 计划快照
3. 计划编译与准入
4. 正文闭环
5. 全量验收
6. 授权后部署
```

每个阶段必须：

- 先写失败测试；
- 保存实现前失败证据到 `var/derive-refactor-stage-N/test-before.log`；
- 只实现该阶段 Boundary；
- 运行阶段指定测试；
- 停止并等待 Reviewer；
- Reviewer 必须返回 `APPROVE`，否则不得进入下一阶段。

禁止：

- 跳过阶段；
- 一次实现多个阶段；
- 修改旧 33-Task 文档作为当前契约；
- commit、push、部署、改线上库、重启线上服务；
- reset/checkout/删除已有未提交改动；
- 放宽 schema、P6 或用 `direct=true` 绕过验证。

## 阶段 1：输出协议闭合

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段1：输出协议闭合。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

只实现：
- ContractViolation、NodeOutputContract、JsonSchemaSupport；
- RouteNarrative、RouteNarrativeContract；
- PlanContractSchemas；
- ROUTE system prompt 与 schema fragment；
- LLM 有效状态读模型；
- 历史错误回放测试。

不得实现 PlanSnapshot、PlanCompiler、正文 writer、P6 或数据库迁移。

执行：
1. 写失败测试；
2. 运行失败并保存 var/derive-refactor-stage-1/test-before.log；
3. 实现最小代码；
4. 运行阶段1测试；
5. 停止，不进入阶段2。

报告必须包含：Boundary、Inputs、Outputs、Pass Criteria 逐项证据、文件、接口、测试证据、偏差、阻塞项、是否 APPROVE。
```

## 阶段 2：计划快照

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段2：计划快照。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

只实现：
- V139 plan snapshot；
- run.active_plan_revision；
- chapters/beat_instance.plan_revision；
- PlanGraphRecords、CompiledStoryPlan、PlanSnapshotService；
- 快照序列化、revision、admit、active 查询测试。

不得实现 PlanCompiler、PlanAuditor、正文 writer 或 P6。

执行：
1. 写失败测试；
2. 运行失败并保存 var/derive-refactor-stage-2/test-before.log；
3. 实现最小代码；
4. 运行阶段2测试；
5. 停止，不进入阶段3。

报告必须包含：Boundary、Inputs、Outputs、Pass Criteria 逐项证据、文件、接口、测试证据、偏差、阻塞项、是否 APPROVE。
```

## 阶段 3：计划编译与准入

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段3：计划编译与准入。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

只实现：
- BeatAssignmentSolver；
- ContinuitySimulator；
- StoryPlanCompiler；
- PlanAuditor；
- PlanProjectionService；
- PlanAdmissionService；
- /gate 兼容适配。

不得实现 SceneWriter、ChapterWriter、FinalAudit 或数据库迁移。

执行：
1. 写失败测试；
2. 运行失败并保存 var/derive-refactor-stage-3/test-before.log；
3. 实现最小代码；
4. 运行阶段3测试；
5. 停止，不进入阶段4。

报告必须包含：Boundary、Inputs、Outputs、Pass Criteria 逐项证据、文件、接口、测试证据、偏差、阻塞项、是否 APPROVE。
```

## 阶段 4：正文闭环

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段4：正文闭环。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

只实现：
- V140 stage ledger；
- SceneWriter；
- ChapterAssembler；
- ChapterDraftCompletionService；
- FullDraftCompletionService；
- DeriveRunOrchestrator；
- FinalAuditService；
- stage 恢复、局部重试、plan revision 防护。

不得修改 StoryContract、PlanSnapshot 或 active_plan_revision。

执行：
1. 写失败测试；
2. 运行失败并保存 var/derive-refactor-stage-4/test-before.log；
3. 实现最小代码；
4. 运行阶段4测试；
5. 停止，不进入阶段5。

报告必须包含：Boundary、Inputs、Outputs、Pass Criteria 逐项证据、文件、接口、测试证据、偏差、阻塞项、是否 APPROVE。
```

## 阶段 5：全量验收

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段5：全量验收。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

只运行：
- 全量测试；
- derive jar 构建；
- 前端构建；
- 10 个 fresh-run fixture；
- 历史错误回放；
- 重启恢复、取消、空章节、unknown enum、duplicate state key、stale revision 故障注入。

不得新增业务功能，不得部署。

执行：
1. 运行阶段5命令；
2. 保存结果；
3. 写 STATUS；
4. 停止，不进入阶段6。

报告必须包含：测试结果、构建结果、每个 fresh-run 结果、故障注入结果、缺陷列表、是否 APPROVE。
```

## 阶段 6：授权后部署

```text
你是 DeriveFlow 重构的独立执行工程师，只执行阶段6：授权后部署。

只有用户在当轮明确授权部署时才执行。未获授权时停止。

先读取通用必读文件。该阶段的详细文件、接口和 Pass Criteria 见：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-derive-refactor-single-pass-plan.md

执行前：
- 记录旧 jar SHA256；
- 确认没有活动 run；
- 备份 jar；
- 备份数据库。

只允许部署 derive app 和必要前端。部署后检查：
- 服务 active；
- 8091；
- Flyway 139/140；
- 新 run；
- 不复用 run58/59/60。

报告必须包含：授权证据、备份、SHA256、服务状态、Flyway、新 run 结果、是否 APPROVE。
```
