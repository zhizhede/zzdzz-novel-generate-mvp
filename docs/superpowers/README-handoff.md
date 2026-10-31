# 随机衍生通用故事合同：实现交接

日期：2026-10-31  
目标读者：没有本仓库上下文的实现 AI

## 1. 你要完成什么

把当前随机衍生系统的悬疑专用故事合同，重构成：

```text
通用故事核心
类型插件合同
可执行叙事义务
⑥b 窗口级校验
插件化 P6/QC
```

最终目标是让具体稿在 P6 之前就能发现并局部修复：

```text
提前泄露
重复 payoff
线索漂移
状态冲突
知识来源缺失
角色铺垫缺失
```

## 2. 必读文件

按这个顺序读，不要跳过：

1. `最高约束.md`
2. `AGENTS.md`
3. `docs/code-standards.md`
4. `docs/STATUS.md`
5. `docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design.md`
6. `docs/superpowers/plans/2026-10-31-derive-generic-story-contract-implementation.md`
7. 施工文档中列出的 Java、XML、SQL 文件

不要先读旧 SPLICE 文档。当前实际主链是随机衍生 run 管线。

## 3. 当前事实基线

当前分支：

```text
codex/remove-main-sample-derivative-chain-20261031
```

当前已知状态：

- ROUTE 提示词与旧 parser 已统一；
- `V142__derive_beat_pool_protected_beat.sql` 已补；
- 宝塔 `novelgen-derive` 已部署；
- Flyway derive schema 当前为 142；
- run63 因 compiler-v1 计划桥接审计失败停在 REWORK；
- run64 在 ⑥b 具体稿阶段被 P6 打回；
- run64 没有正文，也没有进入 ⑧ QC；
- 工作区存在未提交改动。

## 4. 执行方式

严格按施工文档逐任务执行：

```text
Task 1
→ Task 2
→ Task 3
→ ...
→ Task 12
→ Final Verification
```

每个任务必须：

1. 先写失败测试；
2. 运行测试确认失败；
3. 实现最小代码；
4. 运行测试确认通过；
5. 再进入下一个任务。

遇到设计文档、施工文档和代码事实冲突时：

```text
停下来
→ 列出冲突
→ 请求用户决策
```

不要自行猜测，不要扩大重构范围。

## 5. 不允许做的事

- 不得未经用户明确授权 commit；
- 不得未经用户明确授权 push；
- 不得未经用户明确授权部署；
- 不得修改历史 Flyway 迁移；
- 不得删除旧 `StoryContract`；
- 不得删除旧 ROUTE parser；
- 不得删除 P6 或 QC；
- 不得把 `culprit`、`motive`、`method`、`revealChapter` 写进通用核心；
- 不得让模型输出新的 cast、线程 key、线索 key、揭示章或结局事实；
- 不得把旧 PLAN_V2 与 compiler-v1 混跑；
- 不得新增没有读写方的数据库表；
- 不得把所有 ⑥b 错误都留给 P6。

## 6. 最终验收

完成标准：

- 通用核心不出现悬疑专属字段；
- 悬疑字段全部位于 `MysteryProfile`；
- 合同、日程、卷合同、角色合同能投影为通用义务；
- ⑥b 每个 BATCH 窗口都能执行义务校验；
- 提前 payoff、重复 payoff、缺 setup 能在 P6 之前被拦截；
- P6 负责跨章/跨卷/全书审计；
- QC 负责正文兑现、世界状态、时间线和逻辑一致性；
- 旧悬疑 run 可以读取，或有明确迁移/废弃策略；
- 悬疑 4 章真实探针完成；
- 至少一个非悬疑最小探针完成；
- `mvn -q test` 通过；
- `mvn -q -DskipTests package` 通过；
- `git diff --check` 通过。

## 7. 交接原则

这份交接包的目标不是让 AI“自由发挥”，而是让它：

```text
理解设计
遵守边界
按任务施工
先测试后实现
遇到冲突停下来
未经授权不提交、不部署
```

## 8. 已知设计冲突与决议

实现 AI 在 `RomanceProfile` 处暂停是正确行为。

旧 V2 计划曾错误地要求：

```text
RomanceProfile.parseContract(...) -> StoryContract
```

但 `StoryContract` 是悬疑专用合同，包含 `truth/clues/culprit/motive/method/revealChapter`。言情合同不能伪装成它。

决议：

```text
GenreProfile<C extends GenreContract>
MysteryContract 包装旧 StoryContract
RomanceContract 独立建模
GenreContractCodec<C> 负责 JSON 持久化
derive_genre_contract 保存类型合同
旧悬疑 run 通过 MysteryCodec 兼容读取
```

实现 AI 必须以修订后的：

```text
docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design-v2.md
docs/superpowers/plans/2026-10-31-derive-generic-story-contract-implementation-v2.md
```

为准。不得把 `RomanceContract` 强转为 `StoryContract`，也不得为言情合同伪造悬疑字段。

`RomanceContract` 的实际结构、JSON 形状、章号校验、关系线 key 规则、义务投影、⑥b `romanceEvents` 和 P6/QC 接入方式，已补写在 V2 设计文档 §6.3 和施工计划 Task 8。实现 AI 不需要再猜测这些字段类型。

## 9. 当前业务优先级

从 2026-11-01 起，第一目标改为：

```text
一本悬疑短篇 → PASSED
同时一本言情短篇 → PASSED
```

当前优先施工文档：

```text
docs/superpowers/specs/2026-11-01-derive-dual-passed-probes-design.md
docs/superpowers/plans/2026-11-01-derive-dual-passed-probes-implementation.md
```

旧 V2 计划保留作为架构参考，但不能优先于双 PASSED 探针。
