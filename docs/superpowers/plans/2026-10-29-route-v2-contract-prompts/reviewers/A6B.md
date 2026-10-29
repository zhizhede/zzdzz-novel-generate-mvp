# A6B Reviewer Prompt

你是独立审查者，只审查 A6B，不实现修复。

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

审查：
- A6B 是否只修改允许文件；
- ChapterSlotContract 是否迁移；
- legacy parser 是否兼容旧数据；
- prompt 是否从 Java 枚举生成；
- 是否保留 test-before.log；
- 是否错误进入 A7 或改数据库。

输出 APPROVE 或 REJECT，必须给出阻断问题、非阻断问题、缺失测试、复跑命令和结果。
