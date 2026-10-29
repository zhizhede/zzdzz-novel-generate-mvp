# A6B Worker Prompt

你是独立执行工程师，只执行 A6B。A6V2 必须已经 APPROVE。

工作目录：
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp

读取：
1. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\最高约束.md
2. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\AGENTS.md
3. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\code-standards.md
4. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\STATUS.md
5. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\architecture\pipeline-contracts.md
6. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\specs\2026-10-29-route-v2-contract-a6v2.md
7. E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-29-route-v2-contract-a6b-legacy-migration.md

只实现 A6B：
- ChapterSlotContract 状态字段迁移；
- legacy ROUTE parser；
- prompt 枚举；
- 对应测试。

禁止改数据库、迁移、正文 writer、P6、orchestrator。

执行：
1. 先写并运行指定失败测试；
2. 将失败输出保存到 var/task-a6b/test-before.log；
3. 实现最小迁移；
4. 运行：
   $env:JAVA_HOME="D:\Program\Java\jdk-21"
   mvn -q test -pl novelgen-service -am "-Dtest=DeriveBeatRouteServiceTest,ContractPromptArityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
5. 停止，不进入 A7。

完成后报告：Task ID、Boundary、Inputs、Outputs、Pass Criteria 逐项证据、文件、接口、首次失败日志路径、最终测试结果、偏差和阻塞项。
