# 随机衍生系统通用故事合同设计

日期：2026-10-31  
目标读者：没有本仓库上下文的实现 AI  
状态：设计稿，等待用户审阅；本文件不是实现计划，也不是部署授权

## 0. 给实现 AI 的阅读顺序

实现 AI 必须先读以下材料，再修改代码：

1. `最高约束.md`
2. `AGENTS.md`
3. `docs/code-standards.md`
4. `docs/STATUS.md`
5. `docs/superpowers/plans/2026-10-31-random-derive-current-business-handoff.md`
6. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
7. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java`
8. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
9. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
10. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
11. `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java`

不要先读旧 SPLICE 文档，不要假设 SPLICE 是当前主链。当前实际主链是随机衍生 run 管线。

## 1. 业务目标

随机衍生系统的目标不是让模型自由写一段文字，而是：

```text
用户提供数据源
→ 系统抽取素材
→ 系统随机组合故事
→ 系统生成可执行的故事合同
→ 系统规划章节和事件
→ 系统生成具体稿
→ 系统生成正文
→ 系统检查结构、逻辑、连续性和文风
→ 人工做最后润色和交付
```

系统需要解决的核心问题：

- 稳定产出完整小说初稿；
- 长篇不因为上下文过长而写崩；
- 人物、世界、时间、状态、伏笔保持一致；
- 失败可以定位到具体卷、章、拍；
- 失败可以局部回炉，不轻易重写整本书；
- 成本、耗时、token、重试次数可追踪；
- 人工只做最后判断和润色。

本设计只处理“故事合同抽象层”和“具体稿约束层”。它不重写采样、正文、P6、QC 的全部实现。

## 2. 当前系统现状

### 2.1 当前主链

当前随机衍生主链是：

```text
POST /derive/api/derive/runs
→ SAMPLING
→ PLANNING
   SCALE
   CONTRACT
   SCHEDULE
   SPINE
   VOLUMES
   CHARACTERS
   SLOT_ROUTE
   VALIDATE
→ DRAFTING
   ⑥b HEAD
   ⑥b BATCH
→ P6 立项闸
→ ⑦ 正文
→ ⑧ QC
→ PASSED / REWORK / FAILED
```

主要服务：

```text
DeriveSampleService
DeriveStoryPlanService
DeriveContractService
DeriveChapterScheduleService
DeriveBeatRouteService
DeriveDraftService
DeriveGateService
DeriveWriteService
DeriveQcService
DeriveAutoFlowService
```

### 2.2 已有确定性合同

当前已经有合同，但它们偏悬疑推理：

```text
derive_story_contract
derive_chapter_schedule
derive_volume_plan
derive_character_plan
derive_story_thread
derive_beat_pool
beat_instance
```

当前 `StoryContract` 的关键字段：

```text
cast
truth
  culpritKey
  culpritName
  motive
  method
  finalTruth
  finalChoice
  revealChapter
clues
threads
forbidden
volumeTruth
scope
```

这套合同对悬疑推理有效，但不适用于言情、都市、奇幻、冒险等类型。

### 2.3 已发生的实证问题

#### run62

卡在 `SLOT_ROUTE`：

```text
模型输出 beatNarratives/skipped
Java parseRoute 要求 volumeNo/chapters/assignments/syntheticSlots/skipped
```

根因是 ROUTE 提示词/schema 与生产解析器不是同一套契约。

#### run63

完成了部分规划，但在 compiler-v1 计划准入阶段失败：

```text
PLAN_CLUE_PLANT_MISSING
PLAN_CLUE_PAYOFF_MISSING
PLAN_REVEAL_BOUNDARY
PLAN_THREAD_OPEN
PLAN_KNOWLEDGE_SOURCE_MISSING
```

旧 PLAN_V2 路由结果与 compiler-v1 计划图不完全兼容。

#### run64

完成了：

```text
SCALE
CONTRACT
SCHEDULE
SPINE
VOLUMES
CHARACTERS
SLOT_ROUTE
⑥b HEAD
⑥b BATCH
```

但 P6 发现具体稿存在：

```text
第2章提前泄露第3章线索
第2章提前收束
第4章动机缺少前文铺垫
同一事实重复多次
线索 payoff 与日程不一致
角色高光被提前掏空
```

run64 没有进入正文和 ⑧ QC。

## 3. 问题定义

当前问题不是单纯“模型写得差”，而是三层问题叠加：

### 3.1 合同抽象错误

当前合同把两层内容混在一起：

```text
通用故事骨架
悬疑推理类型字段
```

通用部分例如：

```text
人物
状态
关系
线程
事件
章节日程
因果依赖
局部闭合
最终闭合
```

悬疑专用部分例如：

```text
凶手
动机
手法
线索埋设
线索兑现
揭示章
真凶姓名
```

### 3.2 具体稿自由度过高

⑥b 同时要求模型决定：

```text
每拍发生什么
每章具体大纲
人物如何行动
信息何时出现
线索何时埋设
线索何时兑现
状态如何变化
章末造成什么后果
```

Java 侧只检查部分字段，没有把叙事义务变成写入前的硬约束。

### 3.3 检查发生得太晚

当前流程：

```text
合同
→ 路由
→ ⑥b 具体稿
→ P6 才完整检查跨章结构
```

P6 能发现问题，但问题已经在具体稿中形成，回炉粒度不够精确。

## 4. 设计目标

### 4.1 合同分层

将合同拆成：

```text
通用故事核心
类型插件合同
```

通用核心不出现：

```text
culprit
motive
method
revealChapter
```

这些字段只能存在于悬疑插件。

### 4.2 叙事义务可执行

将“这一章必须发生什么/不能发生什么”表示为 Java 可校验的义务，而不是只写在提示词里。

### 4.3 具体稿受约束

⑥b 模型只负责：

```text
把系统给定的义务写成具体场景和章纲
```

模型不能自行改变：

```text
人物身份
核心状态
合同线程
合同线索
揭示章
结局事实
```

### 4.4 类型可扩展

新增言情、都市、奇幻、冒险时：

```text
新增类型插件
新增类型提示词
新增类型校验器
```

不新增一整条独立管线。

### 4.5 兼容现有主链

不要求一次性重写：

```text
SAMPLING
PLANNING
DRAFTING
P6
⑦
⑧
```

先完成合同抽象和 ⑥b 约束，再逐步迁移。

## 5. 非目标

本设计不包含：

- 把系统改成 SPLICE；
- 把文件工作区作为生产主链；
- 删除 P6；
- 删除 QC；
- 让模型负责所有结构字段；
- 一次性支持所有小说类型；
- 立即大规模增加数据库表；
- 修改主站旧样本衍生链；
- 未经用户授权 commit、push、部署。

## 6. 总体架构

目标架构：

```text
采样结果
→ 通用故事核心合同
→ 类型插件合同
→ 通用章节日程
→ 类型结构校验
→ 通用拍路由
→ 通用叙事义务
→ ⑥b 具体稿
→ 窗口级义务校验
→ P6 插件审计
→ 正文
→ QC
```

核心原则：

```text
结构由 Java 决定
叙事表达由模型决定
合同由插件扩展
校验按层执行
```

## 7. 通用故事核心模型

### 7.1 StoryKernel

建议新增强类型模型：

```text
StoryKernel
```

字段：

```text
runId
version
status
scope
entities
relations
states
threads
events
obligations
constraints
closure
schedule
```

`StoryKernel` 是所有类型共用的唯一故事骨架。

### 7.2 StoryEntity

表示故事中的稳定实体：

```text
key
kind
name
attributes
introducedChapter
```

`kind` 示例：

```text
CHARACTER
ORGANIZATION
LOCATION
ITEM
PHENOMENON
```

不要在通用实体里硬编码：

```text
DETECTIVE
SUSPECT
CULPRIT
```

这些应由类型插件给出角色功能。

### 7.3 StoryRelation

表示实体关系：

```text
key
leftKey
rightKey
kind
openedChapter
changedChapters
closedChapter
status
```

### 7.4 StoryState

表示可被后续章节读取的状态：

```text
entityKey
kind
value
effectiveChapter
sourceEventKey
```

状态示例：

```text
LOCATION
PRESENT
ABSENT
DEAD
IDENTITY
POSSESSION_GAIN
POSSESSION_LOSS
RELATIONSHIP
```

### 7.5 StoryThread

通用线程，不再只表示悬疑线索：

```text
threadKey
kind
title
openedChapter
status
ownerKey
targetChapter
contract
```

`kind` 示例：

```text
CLUE
RELATIONSHIP
OBJECT
GOAL
POWER
CONTRACT
```

### 7.6 StoryEvent

事件是故事推进的最小业务单元：

```text
eventKey
chapterNo
kind
actorKeys
targetKeys
goal
result
knowledgeEffects
stateEffects
threadEffects
source
```

`kind` 示例：

```text
SETUP
DISCOVERY
CHOICE
CONFLICT
COST
REVEAL
CLOSURE
TRANSITION
```

### 7.7 StoryObligation

这是本设计最重要的新抽象。

一行义务表示：

```text
某章必须完成某件事，或不得完成某件事
```

字段：

```text
obligationKey
chapterNo
kind
targetKey
required
forbidden
severity
sourceContract
payload
```

`kind` 示例：

```text
PLANT_THREAD
ADVANCE_THREAD
PAYOFF_THREAD
CLOSE_THREAD
INTRODUCE_ENTITY
CHANGE_STATE
KNOWLEDGE_WRITE
KNOWLEDGE_READ
CHARACTER_CHOICE
CHARACTER_COST
LOCAL_CLOSURE
FINAL_CLOSURE
GENRE_EVENT
```

悬疑插件可以把：

```text
PLANT_THREAD / PAYOFF_THREAD
```

映射成：

```text
线索埋设 / 线索兑现
```

但通用核心不出现“凶手”“作案手法”等字段。

### 7.8 StoryConstraint

表示禁止事项：

```text
constraintKey
scope
chapterFrom
chapterTo
kind
targetKey
message
```

示例：

```text
禁止在第3章之前写某人物死亡
禁止在第5章之前关闭某线程
禁止在第2章提前揭晓某类型事件
```

### 7.9 StoryClosure

表示结局义务：

```text
closureKey
kind
targetKey
requiredChapter
required
proofEventKeys
```

示例：

```text
主线必须闭合
关系必须闭合
世界规则必须交代
主角选择必须产生后果
```

## 8. 类型插件模型

### 8.1 GenreProfile

建议新增：

```text
GenreProfile
```

接口职责：

```text
提供类型名
提供合同 schema
提供合同生成提示词
提供合同解析器
提供合同校验器
提供结构槽模板
提供类型 P6 判据
提供类型 QC 判据
```

### 8.2 MysteryProfile

当前悬疑推理逻辑迁移到：

```text
MysteryProfile
```

插件字段：

```text
truth
  culpritKey
  culpritName
  motive
  method
  finalTruth
  finalChoice
  revealChapter
clues
  clueKey
  clue
  plantedChapter
  payoffChapter
  ownerKey
falseAnswer
suspects
```

插件职责：

```text
把悬疑合同投影成通用 obligation
把悬疑线索投影成通用 thread/event
生成悬疑专属校验错误
```

### 8.3 RomanceProfile

未来插件字段示例：

```text
relationshipStart
misunderstanding
attachmentEscalation
conflictPeak
confession
reconciliation
relationshipClosure
```

映射到通用：

```text
relationship thread
character choice
character cost
relationship state change
local closure
final closure
```

### 8.4 FantasyProfile

未来插件字段示例：

```text
worldRule
powerUnlock
powerCost
factionChange
finalTrial
finalChoice
```

映射到通用：

```text
world thread
state change
character cost
local closure
final closure
```

## 9. 合同生成流程

### 9.1 SCALE

保持现有职责：

```text
判断章数、卷数、总字数带
```

输出仍然是 `ScopeContract`。

### 9.2 CONTRACT

改为：

```text
读取 GenreProfile
→ 生成类型合同
→ 解析并冻结
→ 投影为 StoryKernel
→ 校验通用合同
→ 校验类型合同
```

悬疑插件生成：

```text
Truth
Clues
Threads
Forbidden
VolumeTruth
```

通用核心生成：

```text
Entities
Relations
States
Events
Obligations
Constraints
Closure
```

### 9.3 SCHEDULE

当前 `ChapterScheduleEntry` 保留，但增加通用语义：

```text
chapterNo
volumeNo
reveal
mustPlant
mustPayoff
requiredObligations
forbiddenObligations
```

旧字段继续兼容悬疑插件，新字段用于通用义务。

### 9.4 SPINE/VOLUMES/CHARACTERS

保留现有卷和角色规划，但输出必须映射到通用义务。

例如角色合同中的：

```text
requiredSetup
highlightChoice
highlightCost
payoffProof
arcBefore
arcAfter
```

应投影为：

```text
INTRODUCE_ENTITY
CHARACTER_CHOICE
CHARACTER_COST
PAYOFF_THREAD
CHANGE_STATE
```

### 9.5 SLOT_ROUTE

拍路由继续由 Java 负责：

```text
章号
拍 seq
拍角色
依赖
状态
知识
线程
```

模型只提供叙事效果，不再决定结构字段。

短期实现可以保留旧 ROUTE 契约，但必须满足：

```text
提示词
schema
Java parser
```

三者使用同一套字段，不能再出现 run62 的错配。

## 10. ⑥b 具体稿重构

### 10.1 ⑥b 输入

每窗输入必须包含：

```text
全局实体表
人物 persona
本窗章节计划
本窗义务
本窗禁止事项
已发生状态
已知知识
线程状态
类型插件合同
前批章纲摘要
```

### 10.2 ⑥b 输出

模型只输出：

```text
chapters
  no
  title
  draft_outline
  time
  state_changes
  knowledge_events
  thread_events
  closingActs
beats
  seq
  filled
```

模型不得输出：

```text
新的 cast
新的线索 key
新的线程 key
新的真凶
新的动机
新的手法
新的揭示章
```

### 10.3 Java 窗口校验

每个 BATCH 窗口输出后，必须立即校验：

```text
义务是否全部完成
禁止事项是否被违反
线程是否提前关闭
状态是否与前批冲突
知识读取是否早于知识写入
揭示是否越过揭示章
是否新增合同外实体/线程/线索
```

校验失败时：

```text
只回炉当前窗
不默认清空整本书
错误必须包含章号、义务 key、模型输出路径
```

### 10.4 窗口校验示例

如果合同规定：

```text
第3章 PAYOFF GM-CLUE-01
```

而模型在第2章写出：

```text
明柯已经知道陆青打卡记录显示21:25离店
```

Java 应立即拒绝，错误应类似：

```text
OBLIGATION_EARLY_PAYOFF
chapter=2
thread=GM-CLUE-01
expectedPayoffChapter=3
```

如果合同规定：

```text
第4章 CHARACTER_CHOICE C1
requiredSetup=[旧史铺垫]
```

而第1到3章没有对应义务，窗口校验应拒绝：

```text
OBLIGATION_MISSING_SETUP
chapter=4
entity=C1
missingSetup=旧史铺垫
```

## 11. P6 与 QC 分工

### 11.1 ⑥b 窗口校验

负责：

```text
字段合法性
义务完成
禁止事项
线程状态
知识来源
状态冲突
揭示边界
```

### 11.2 P6

负责跨章、跨卷、全书级审计：

```text
主线是否闭合
角色职能是否成立
类型合同是否自洽
因果链是否成立
母题是否重复
体量预算是否合理
```

P6 不再是所有结构问题的第一道防线。

### 11.3 ⑧ QC

负责正文层：

```text
章纲是否被正文兑现
正文是否出现空章
禁词是否泄漏
世界状态是否一致
时间是否一致
逻辑是否自洽
文风是否符合目标
```

## 12. 数据模型与表

### 12.1 第一阶段不强制新增大表

第一阶段可以继续使用：

```text
derive_story_contract
derive_chapter_schedule
derive_volume_plan
derive_character_plan
derive_story_thread
derive_beat_pool
beat_instance
```

但必须把通用合同作为 JSON 或强类型投影保存，不能继续把悬疑字段当通用字段。

### 12.2 建议新增表：derive_story_obligation

当义务需要跨窗复用、审计和人工纠偏时，新增：

```text
derive_story_obligation
```

字段建议：

```text
id
run_id
obligation_key
chapter_no
kind
target_key
required
forbidden
severity
source_contract
status
payload
created_time
updated_time
```

约束：

```text
(run_id, obligation_key) 唯一
chapter_no 大于 0
kind 非空
status 只允许 PLANNED/FULFILLED/VIOLATED/SKIPPED
```

### 12.3 是否新增 derive_story_event

可以在第二阶段新增：

```text
derive_story_event
```

但第一阶段优先复用：

```text
beat_instance
chapter plan_contract
world_states
```

不要在没有稳定读写方的情况下提前建表。

### 12.4 类型插件数据

类型插件合同建议保存在：

```text
genre
genre_payload
```

不要为每个插件复制一套核心表。

## 13. 兼容策略

### 13.1 旧悬疑 run

旧 run 继续可读：

```text
StoryContract
ChapterSchedule
VolumePlan
CharacterPlan
Thread
BeatInstance
```

但标记为：

```text
genre=MYSTERY
contractVersion=legacy
```

### 13.2 新 run

新 run 必须有：

```text
genre
genreContractVersion
storyKernelVersion
```

### 13.3 混用禁止

禁止：

```text
新合同 + 旧提示词
旧合同 + 新 parser
新义务 + 旧 P6
旧日程 + 新正文路径
```

## 14. 错误模型

统一错误结构：

```text
code
message
chapterNo
volumeNo
obligationKey
entityKey
threadKey
eventKey
source
```

必须区分：

```text
MODEL_OUTPUT_INVALID
OBLIGATION_MISSING
OBLIGATION_EARLY
OBLIGATION_VIOLATED
STATE_CONFLICT
KNOWLEDGE_SOURCE_MISSING
GENRE_CONTRACT_INVALID
LEGACY_COMPAT_ERROR
```

模型输出错误要能回喂给模型；Java 结构错误不能让模型自由重写合同。

## 15. 测试设计

### 15.1 通用核心单测

覆盖：

```text
StoryKernel 序列化
Entity 唯一键
Relation 唯一键
State 生效章
Thread 状态机
Obligation 唯一性
Constraint 范围
Closure 必填字段
```

### 15.2 插件单测

MysteryProfile 覆盖：

```text
凶手属于 cast
线索埋设章合法
线索兑现章晚于埋设章
揭示章不早于最晚埋设章
线程与线索 key 一致
```

### 15.3 投影单测

覆盖：

```text
悬疑合同 → 通用义务
角色合同 → 通用义务
卷合同 → 通用义务
日程 → 通用义务
```

### 15.4 ⑥b 窗口校验单测

必须覆盖：

```text
提前 payoff
重复 payoff
缺少 setup
提前 reveal
状态冲突
知识来源缺失
新增合同外实体
```

### 15.5 旧链回归

必须覆盖：

```text
旧 ROUTE 提示词
旧 ROUTE parser
旧 run 详情读取
旧 P6 读取
旧 QC 读取
```

### 15.6 真实探针

至少准备：

```text
悬疑 4 章探针
言情 4 章探针
奇幻 4 章探针
```

每个探针必须证明：

```text
合同生成
日程投影
义务生成
⑥b 窗口校验
P6
正文
QC
```

## 16. 实施阶段

### 阶段 1：抽象合同，不改变当前行为

交付：

```text
StoryKernel 类型
GenreProfile 接口
MysteryProfile
旧 StoryContract 到 MysteryProfile 的适配
```

验收：

```text
现有悬疑 run 行为不变
单测全绿
```

### 阶段 2：通用义务投影

交付：

```text
StoryObligation
日程到义务投影
角色/卷合同到义务投影
义务读模型
```

验收：

```text
run64 类型问题能被窗口校验提前发现
```

### 阶段 3：⑥b 窗口校验

交付：

```text
义务校验器
窗口局部回炉
结构化错误
```

验收：

```text
提前 payoff、重复 payoff、缺 setup 不再等到 P6
```

### 阶段 4：P6/QC 插件化

交付：

```text
通用 P6 判据
Mystery P6 判据
通用 QC 判据
Mystery QC 判据
```

### 阶段 5：新增第二个类型插件

优先选择：

```text
RomanceProfile
```

因为它能验证：

```text
没有 culprit/motive/method/revealChapter
是否仍能完成合同、义务、正文、QC
```

## 17. 实现 AI 的执行规则

1. 先读完本设计和项目铁律。
2. 不得直接删除旧 `StoryContract`。
3. 不得把悬疑字段继续放进通用核心。
4. 不得让模型输出新的 cast、线程、线索、揭示章。
5. 不得把 ⑥b 的问题全部留给 P6。
6. 不得新增没有读写方的表。
7. 不得把旧 PLAN_V2 与 compiler-v1 混跑。
8. 每个阶段必须先写测试，再改实现。
9. 每个阶段必须跑 `mvn -q test`。
10. 涉及数据库迁移必须遵循 Flyway 版本规则。
11. 不得未经用户明确允许 commit、push、部署。

## 18. 完成标准

只有满足以下条件，才能说通用故事合同重构完成：

- 通用核心不出现悬疑专属字段；
- 悬疑字段全部位于 MysteryProfile；
- 通用义务可以表达线索、关系、角色、世界、奇幻能力等不同类型；
- ⑥b 每个窗口都能提前发现义务违规；
- P6 不再承担所有结构错误的第一发现权；
- QC 负责正文兑现和逻辑一致性；
- 旧悬疑 run 可以读取，或有明确迁移/废弃策略；
- 至少一个非悬疑插件可以生成合同并完成最小探针；
- 全量测试通过；
- 没有未经授权的 commit、push、部署。

## 19. 当前分支与已知部署状态

当前工作树分支：

```text
codex/remove-main-sample-derivative-chain-20261031
```

截至本设计编写时：

- ROUTE 提示词/解析器错配已修复；
- `V142__derive_beat_pool_protected_beat.sql` 已补；
- 宝塔 `novelgen-derive` 已部署；
- Flyway schema 已到 142；
- run63 因 compiler-v1 桥接审计失败停在 REWORK；
- run64 在 ⑥b 具体稿阶段被 P6 打回；
- run64 没有正文，也没有进入 ⑧ QC；
- 未 commit、未 push。

## 20. 给实现 AI 的最终提醒

本设计的最终判断是：

```text
当前系统不是没有合同。
当前系统是有合同，但合同不够通用、不够可执行、检查得太晚。
```

实现目标不是继续堆提示词，而是建立：

```text
通用故事核心
类型插件
可执行叙事义务
窗口级确定性校验
分层 P6/QC
```

这套抽象完成后，悬疑推理只是第一个插件，而不是整个系统的地基。
