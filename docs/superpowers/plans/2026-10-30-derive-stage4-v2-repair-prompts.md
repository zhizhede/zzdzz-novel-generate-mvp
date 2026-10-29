# DeriveFlow 阶段 4 V2 修复 Prompt

> 配套计划：`docs/superpowers/plans/2026-10-30-derive-stage4-v2-repair-plan.md`  
> 本 Prompt 只修复阶段 4 的两个阻断问题。禁止进入阶段 5。

## Worker Prompt

```text
你是独立执行工程师，只执行阶段4 V2修复，不实现新的计划编译、正文或数据库能力。

工作目录：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp

先完整读取：
1. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
2. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
3. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
4. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
5. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
6. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-30-derive-stage4-v2-repair-plan.md

执行顺序：R1 -> R2 -> R3。

R1：DRAFT revision 落账所有权
- 修改 DraftStageHandler 和 DraftStageHandlerTest；
- 不再用 handler 预读的 active revision 覆盖 result；
- 成功时必须使用 FullDraftCompletionService.DraftCompletionResult.planRevision()；
- missingChapters 非空仍返回 DRAFT_INCOMPLETE；
- 不修改 FullDraftCompletionService、PlanSnapshotService、V140 或数据库；
- 增加真实测试：预读/完成结果 revision 不一致时，最终 outputRevision 必须等于完成结果。

R2：FINAL_AUDIT 快照加载失败分类
- 修改 FinalAuditService、FinalAuditServiceTest、FinalAuditStageHandlerTest；
- 把 requireActive(runId) 和 loadCompiled(runId, active.getRevision()) 纳入同一失败分类；
- BizException、数据库 RuntimeException、NPE、契约校验异常都必须返回 FinalQuality.FAILED；
- 至少产生 code=FINAL_AUDIT_BLOCKER、path=run[<runId>] 的 issue；
- FinalAuditStageHandler 不得把这种失败写成 STAGE_HANDLER_FAILED；
- 不修改终检的 PASS/ADVISORY/DEGRADED 正常路径；
- 不修改计划、正文、快照或 active pointer。

R3：回归验证
- 先写新测试并运行，保存实现前失败证据到 var/stage4-v2/test-before.log；
- 再实现最小修复；
- 保存定向测试日志到 var/stage4-v2/test-after-r1.log 和 test-after-r2.log；
- 最后运行全量 mvn -q test，保存 var/stage4-v2/test-full.log；
- 追加 docs/STATUS.md，写清修改文件、测试命令、退出码、遗留和是否允许进入阶段5。

定向命令：
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=DraftStageHandlerTest,FullDraftCompletionServiceTest,FinalAuditServiceTest,FinalAuditStageHandlerTest,DeriveRunOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false"

全量命令：
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test

必须满足：
- 实现前测试确实变红；
- 定向测试和全量测试通过；
- 未修改 Boundary 之外文件；
- 未 commit、push、部署、改库、重启；
- 未进入阶段5。

完成后报告：
- R1/R2/R3 状态；
- Boundary、Inputs、Outputs、Pass Criteria 逐项证据；
- 修改文件；
- 实现前失败日志和实现后测试命令、退出码、结果；
- 是否可以进入阶段5。
```

## Reviewer Prompt

```text
你是独立审查者，只审查阶段4 V2修复，不实现修复。

工作目录：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp

先读取：
1. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
2. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
3. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
4. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
5. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
6. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-30-derive-stage4-v2-repair-plan.md

只审查以下文件是否实现契约：
- novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandler.java
- novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/DraftStageHandlerTest.java
- novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/FinalAuditService.java
- novelgen-service/src/test/java/com/zzdzz/novelgen/derive/draft/FinalAuditServiceTest.java
- novelgen-service/src/test/java/com/zzdzz/novelgen/derive/orchestration/FinalAuditStageHandlerTest.java
- docs/STATUS.md

逐项检查：
1. DRAFT DONE 是否直接使用 completeAll() 返回的 result.planRevision()；
2. 是否仍有任何旧 active revision 覆盖 result revision；
3. missingChapters 非空是否仍返回 DRAFT_INCOMPLETE；
4. requireActive() 和 loadCompiled() 是否在同一个失败分类边界；
5. loadCompiled() 的 BizException、数据库异常、NPE 是否都返回 FAILED；
6. 是否产生 FINAL_AUDIT_BLOCKER，而不是逃逸为 STAGE_HANDLER_FAILED；
7. revision 不一致测试是否真实存在并能证明旧实现失败；
8. loadCompiled 失败测试是否覆盖 BizException、DB RuntimeException、NPE；
9. 实现前失败日志是否真实保存；
10. 定向测试和全量测试是否真实通过；
11. 是否修改 Boundary 之外文件；
12. 是否进入阶段5、部署、改数据库、commit 或 push。

输出格式：
- Decision: APPROVE 或 REJECT
- 阻断问题：absolute path:line + 事实 + 影响 + 修复动作
- 非阻断问题
- 缺失测试
- 已复跑命令与退出码/结果
- 是否允许进入阶段5

审查原则：
- 只看测试绿不算通过，必须核对代码所有权和错误分类链路；
- 只看时间戳不算实现前失败，必须核对日志内容；
- 任何 Boundary 违规、阶段5接入或缺少关键测试都必须 REJECT。
```
