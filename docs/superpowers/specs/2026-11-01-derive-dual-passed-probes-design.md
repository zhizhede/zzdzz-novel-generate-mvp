# 随机衍生双 PASSED 短篇探针业务设计

日期：2026-11-01  
目标读者：完全没有本仓库上下文、负责实现的 AI  
状态：业务优先设计稿；不是部署授权，也不是 commit/push 授权  
前置文档：

```text
docs/superpowers/README-handoff.md
docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design-v2.md
docs/superpowers/plans/2026-10-31-derive-generic-story-contract-implementation-v2.md
```

## 0. 本设计的第一目标

本次实现不以“新增更多类型插件”为第一目标，而以真实业务结果为第一目标：

```text
一本悬疑短篇从建 run 一直跑到 PASSED
同时一本言情短篇从建 run 一直跑到 PASSED
```

只有这两个真实探针都通过，才允许继续做：

```text
FantasyProfile
都市Profile
100+章长篇
批量运行
更多数据库抽象
```

## 1. 为什么目标必须改成双 PASSED

最新宝塔实测已经证明：

```text
run64：P6 通过，但总字数7329超过3000~5000，QC失败
run65：第5章 FINAL_CLOSURE 缺失，未进入正文和QC
run66：第5章闭合义务缺失，未进入正文和QC
```

因此当前最短业务路径不是继续扩展合同抽象，而是：

```text
修闭合
修字数预算
修 direct 语义
跑通两本短篇
再扩展类型
```

## 2. 交付等级

### 2.1 DRAFT

```text
允许模型输出不完整
允许 P6/QC 有 BLOCKER
只用于人工查看和调试
不得作为业务成功
```

### 2.2 REVIEWABLE

```text
合同冻结
日程完成
义务完成
正文完整
字数在硬范围内
P6 可以 REWORK，但必须有可执行指令
QC 可以有 ADVISORY，但不得有 BLOCKER
```

### 2.3 PASSED

只有同时满足以下条件：

```text
run.status = PASSED
正文非空且章数等于合同章数
总字数在 wordsMin..wordsMax
P6 verdict = PASS
QC verdict = PASS
所有 required 义务有证据
所有 required 线程关闭
最终闭合有结构化证据
没有 BLOCKER
```

## 3. 两个探针的固定业务参数

### 3.1 悬疑探针

```json
{
  "genre": "MYSTERY",
  "wordsMin": 3000,
  "wordsMax": 5000,
  "tags": ["悬疑"],
  "pace": "0.75",
  "seed": 450067,
  "lenCheck": true,
  "antiRepro": false
}
```

要求：

```text
5章短篇
单案
无死亡
无超自然
结局闭合
```

### 3.2 言情探针

```json
{
  "genre": "ROMANCE",
  "wordsMin": 3000,
  "wordsMax": 5000,
  "tags": ["言情"],
  "pace": "0.50",
  "seed": 450068,
  "lenCheck": true,
  "antiRepro": false
}
```

要求：

```text
7章短篇
一条主关系线
误会、升级、冲突、告白、和好、结局闭合
不得出现凶手、动机、手法、线索、揭示章等悬疑字段
```

## 4. 当前必须修复的三类业务阻塞

### 4.1 闭合阻塞

run65/run66 的失败不是“模型格式错误”，而是：

```text
揭示章没有提供结构化闭合事实
窗口校验只报告缺失，没有定向修复信息
未来章节义务可能被当前窗口提前报错
```

#### 修复目标

新增闭合合同：

```text
StoryClosureContract
```

字段：

```text
closureKey
chapterNo
targetKey
kind
requiredActs
requiredTags
proofEventKeys
sourceContract
```

悬疑探针的揭示章必须提供：

```json
{
  "closingActs": ["CULPRIT", "MOTIVE", "METHOD"],
  "closureEvidence": {
    "culpritKey": "C3",
    "motiveTag": "合同中的motiveTag",
    "methodTag": "合同中的methodTag",
    "proofEventKeys": ["GM-CLUE-01"]
  }
}
```

言情探针的结局章必须提供：

```json
{
  "closingActs": ["RELATIONSHIP_DECISION"],
  "closureEvidence": {
    "relationshipThreadKey": "REL-C1-C2",
    "proofEventKeys": ["ROM-STAGE-CONFESSION"]
  }
}
```

Java 校验结构化字段，不要求自然语言逐字包含标签。

#### 窗口规则

```text
义务章号在窗口内 → 当前窗口校验
义务章号在窗口后 → DEFERRED
义务章号在窗口前且未完成 → 已失败，不得静默跳过
```

### 4.2 字数阻塞

run64 证明：

```text
P6 PASS
QC FAIL
总字数7329，要求3000~5000
```

#### 修复目标

新增：

```text
WordBudgetGuard
```

每个 BATCH 窗口落库前计算：

```text
totalCharCount
windowCharCount
remainingChapters
chapterAvg
projectedMin
projectedMax
```

硬失败：

```text
totalCharCount > wordsMax
projectedMax > wordsMax
```

错误：

```text
BUDGET_WINDOW_OVERSHOOT
BUDGET_PROJECTED_OVERSHOOT
```

回炉范围：

```text
只重做当前窗口
或压缩剩余窗口
不得默认重写整本书
```

### 4.3 direct 模式阻塞

run65/run66 使用了：

```text
direct=true
```

当前 direct 可能跳过 P6，甚至跳过部分确定性审计。

#### 修复目标

固定 direct 语义：

```text
direct=true 只跳过 LLM 语义审稿
不得跳过：
  StoryContractValidator
  StoryObligationProjector
  DraftWindowObligationValidator
  ClosureWindowValidator
  WordBudgetGuard
  script QC
```

真实双 PASSED 探针必须使用：

```text
direct=false
```

direct=true 只能作为额外草稿探针，不能作为 PASSED 验收。

## 5. 两本探针的完整主链

```text
建 run
→ SAMPLING
→ SCALE
→ CONTRACT
→ SCHEDULE
→ SPINE
→ VOLUMES
→ CHARACTERS
→ SLOT_ROUTE
→ VALIDATE
→ DRAFTING
   HEAD
   BATCH
→ P6
→ 正文
→ QC
→ PASSED
```

每个阶段必须有：

```text
状态
输入合同
输出产物
失败原因
回炉目标
成本读数
```

## 6. 悬疑探针的验收

必须同时满足：

```text
genre=MYSTERY
5章
总字数3000~5000
正文每章非空
P6 PASS
QC PASS
run.status=PASSED
```

结构验收：

```text
每条 required clue 有 PLANT/PAYOFF 证据
revealChapter 有 CULPRIT/MOTIVE/METHOD 证据
reveal 前没有 solution 泄露
所有 required thread 关闭
所有状态变化有来源事件
```

## 7. 言情探针的验收

必须同时满足：

```text
genre=ROMANCE
7章
总字数3000~5000
正文每章非空
P6 PASS
QC PASS
run.status=PASSED
```

结构验收：

```text
REL-C1-C2 开启
误解发生
关系升级发生
冲突峰值发生
告白发生
和好发生
关系结局闭合
```

禁止验收：

```text
不得出现 culprit
不得出现 motive
不得出现 method
不得出现 clue
不得出现 revealChapter
```

## 8. P6/QC 的最低通过口径

### P6

```text
合同合法
义务存在
日程与义务一致
线程开闭正确
闭合合同可执行
体量预算可执行
```

### QC

```text
正文兑现章纲
义务有证据
世界状态无硬冲突
总字数不超限
逻辑无 BLOCKER
类型专属审稿通过
```

## 9. 失败回炉

```text
合同失败 → 重做 CONTRACT
日程失败 → 重做 SCHEDULE
义务失败 → 重做当前 BATCH 窗口
闭合失败 → 重做闭合所在窗口
预算失败 → 压缩当前或剩余窗口
P6失败 → 按 repairTarget 回炉
QC失败 → 按问题类型回炉或转人工
```

禁止：

```text
所有失败都清空整本书
所有失败都重新采样
direct=true 绕过结构校验
模型自由改写冻结合同
```

## 10. 数据与合同要求

必须使用：

```text
run.genre
run.story_kernel_version
run.genre_contract_version
derive_genre_contract
derive_story_obligation
derive_story_obligation_evidence
```

合同版本必须一致：

```text
StoryContract.version
GenreContract.contract_version
ChapterSchedule.contractVersion
```

当前 run66 出现过：

```text
StoryContract.version=1
GenreContract.contract_version=0
SCHEDULE_STALE
```

这必须在双 PASSED 探针前修复。

## 11. 真实探针执行要求

两个探针都必须：

```text
登录 derive API
创建真实 run
启动 auto
轮询 auto-progress
读取数据库合同、义务、证据
读取 P6 gate review
读取 QC report
检查每章正文非空
检查 run.status
```

报告必须包含：

```text
runId
genre
合同版本
义务数量
证据数量
窗口校验
P6
正文章数
总字数
QC
最终状态
失败原因
```

## 12. 实施顺序

### 阶段 A：修闭合

```text
StoryClosureContract
ClosureWindowValidator
结构化闭合字段
局部闭合回炉
```

### 阶段 B：修预算

```text
WordBudgetGuard
窗口投影
超限局部回炉
```

### 阶段 C：修 direct

```text
direct 只跳过 LLM 审稿
确定性审计全部保留
```

### 阶段 D：修合同版本

```text
统一 story/genre/schedule 版本
消除 SCHEDULE_STALE
```

### 阶段 E：悬疑 PASSED

```text
真实 run
P6 PASS
QC PASS
run.status=PASSED
```

### 阶段 F：言情 PASSED

```text
真实 run
RomanceContract
RomanceGateAuditor
RomanceQcAuditor
P6 PASS
QC PASS
run.status=PASSED
```

## 13. 实现 AI 的硬规则

1. 以本业务设计为准，不以 V2 的类型扩展优先级为准。
2. 先修闭合、预算、direct、版本一致性。
3. 每个阶段先写失败测试。
4. 不得把 `RomanceContract` 强转成 `StoryContract`。
5. 不得用 `text.contains(targetKey)` 判断义务。
6. 不得构造空 `StoryKernel`。
7. 不得未经用户授权 commit、push、部署。
8. 遇到设计与代码冲突时停止并报告。

## 14. 完成标准

只有以下条件全部满足，V3 才算完成：

```text
悬疑真实 run PASSED
言情真实 run PASSED
两本正文完整
两本总字数在范围内
两本 P6 PASS
两本 QC PASS
义务 payload 保留
义务证据落库
闭合有结构化证据
预算超限在窗口阶段拦截
direct 不绕过确定性审计
旧 run 可读取
全量测试通过
打包通过
没有未经授权的 commit、push、部署
```

## 15. 最终业务判断

当前系统不能把“新增更多合同类型”当成第一目标。

第一目标必须是：

```text
悬疑短篇 PASSED
言情短篇 PASSED
```

这两本跑通后，才证明：

```text
合同
义务
证据
闭合
预算
P6
QC
真实正文
人工终稿入口
```

真正组成了可交付的小说生产线。
