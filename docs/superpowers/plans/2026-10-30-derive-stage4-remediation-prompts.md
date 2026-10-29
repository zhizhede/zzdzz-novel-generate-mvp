# DeriveFlow 阶段 4 修复 Prompt

> 使用 `2026-10-30-derive-stage4-remediation-plan.md`。本修复只重做阶段 4；不进入阶段 5。

## Worker Prompt

```text
你是独立执行工程师，只执行阶段4修复，不实现新的计划编译、正文或数据库能力。

工作目录：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp

先完整读取：
1. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
2. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
3. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
4. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
5. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
6. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-30-derive-stage4-remediation-plan.md

按 F1 → F2 → F3 → F4 执行。

F1 修复 FinalAudit quality：
- BLOCKER 必须返回 FinalQuality.FAILED；
- active revision 读取失败必须返回 FAILED；
- FinalAuditStageHandler 只能对 FAILED/BLOCKER 报告返回 StageOutcome.failed。

F2 修复 DRAFT stage：
- FullDraftCompletionService.missingChapters 为空时，DRAFT 才能 DONE；
- missing 非空时必须返回 DRAFT_INCOMPLETE，路径为 chapter[<missingNo>]；
- FINAL_AUDIT 不得推进到 COMPLETE。

F3 修复异常分类：
- PLAN_REVISION_STALE 继续抛出；
- SceneDraftException 允许局部重试；
- 数据库异常、NPE、契约异常原样抛出，禁止改写成 DRAFT_CHAPTER_EMPTY；
- 删除 ChapterDraftCompletionService 中永远不会执行的 sceneFailures 死代码。

F4 只运行回归与全量测试，不新增功能。

先写失败测试，运行并保存结果；再实现最小修复；最后运行：
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=FinalAuditServiceTest,FinalAuditStageHandlerTest,DraftStageHandlerTest,FullDraftCompletionServiceTest,ChapterDraftCompletionServiceTest,SceneIsolationCompletionServiceTest,StageRunStateServiceTest,DeriveRunOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false"

然后：
mvn -q test

禁止：
- git reset --hard、git checkout --、删除未跟踪文件；
- commit、push、部署、改线上库、重启线上服务；
- 修改 StoryContract、CompiledStoryPlan、PlanSnapshotService、run.active_plan_revision；
- 进入阶段5。

完成后报告：
- F1/F2/F3/F4 状态；
- Boundary、Inputs、Outputs、Pass Criteria 证据；
- 修改文件；
- 首次失败日志；
- 最终测试命令、退出码和结果；
- 是否可以进入阶段5。
```

## Reviewer Prompt

```text
你是独立审查者，只审查阶段4修复，不实现修复。

工作目录：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp

读取：
1. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
2. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
3. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
4. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
5. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
6. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-30-derive-stage4-remediation-plan.md

逐项检查：
1. BLOCKER 是否产生 FinalQuality.FAILED；
2. active revision 失败是否产生 FAILED；
3. missing chapter 是否阻止 DRAFT DONE；
4. DRAFT DONE 是否只在 missingChapters 为空时发生；
5. DB/NPE/契约异常是否保留原异常；
6. stale revision 是否继续抛出；
7. 死代码是否删除；
8. 定向测试与全量测试是否通过；
9. 是否越界进入阶段5；
10. 是否误改 StoryContract、PlanSnapshot 或 active revision。

输出：
- APPROVE / REJECT
- 阻断问题（absolute path:line）
- 非阻断问题
- 缺失测试
- 复跑命令与结果
- 是否允许进入阶段5。
```
