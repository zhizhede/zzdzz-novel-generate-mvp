# 随机衍生通用故事合同设计 V2

日期：2026-10-31  
目标读者：完全没有本仓库上下文、负责实现的 AI  
状态：设计稿；不是部署授权，也不是 commit/push 授权  
前置文档：`docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design.md`

## 0. 给实现 AI 的硬性阅读顺序

实现 AI 在修改任何代码前必须按顺序读取并摘录关键事实：

1. `最高约束.md`
2. `AGENTS.md`
3. `docs/code-standards.md`
4. `docs/STATUS.md`
5. `docs/superpowers/README-handoff.md`
6. `docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design.md`
7. `docs/superpowers/plans/2026-10-31-derive-generic-story-contract-implementation.md`
8. `docs/superpowers/plans/2026-10-31-random-derive-current-business-handoff.md`

必须实际读取以下代码，不得只凭本文档猜测：

```text
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveSampleService.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreProfile.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryProfile.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryKernel.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryObligationProjector.java
novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidator.java
```

不要先读旧 SPLICE 文档，不要把 SPLICE 或文件工作区当成当前生产主链。

## 1. 本文档要解决的真实问题

### 1.1 业务目标

随机衍生系统要交付的不是“模型自由写一段文字”，而是：

```text
用户选择数据源
→ 素材抽取、无指向化、打标
→ 随机采样
→ 生成故事合同
→ 投影章节日程和叙事义务
→ 生成具体稿
→ 生成正文
→ 结构/逻辑/连续性/文风检查
→ 人工终稿
```

核心质量目标：

- 能稳定产出完整小说初稿；
- 人物、世界、时间、状态、知识、线程保持一致；
- 失败可以定位到卷、章、拍和具体义务；
- 失败优先局部回炉，不轻易清空整本书；
- token、耗时、重试和失败原因可追踪；
- 人工只做最后判断和润色。

### 1.2 已确认的当前缺陷

#### 缺陷 A：合同是悬疑专用的

当前 `StoryContract` 直接包含：

```text
culpritKey
culpritName
motive
method
finalTruth
finalChoice
revealChapter
clues
plantedChapter
payoffChapter
```

这些字段对悬疑推理有效，但对言情、都市、奇幻、冒险不通用。

#### 缺陷 B：具体稿在 P6 前过度自由

run64 已实证出现：

```text
第2章提前泄露第3章线索
第2章提前收束
第4章动机缺少前文铺垫
同一事实重复多次
线索 payoff 与日程不一致
角色高光被提前掏空
```

这些问题在 ⑥b 已经形成，P6 才发现，回炉粒度不够精确。

#### 缺陷 C：当前实现只完成了一半抽象

当前工作区已经新增了：

```text
StoryKernel
StoryObligation
GenreProfile
MysteryProfile
StoryObligationProjector
DraftWindowObligationValidator
GenreGateAuditor
GenreQcAuditor
derive_story_obligation
```

但当前实现还有以下实际问题：

```text
P6/QC 重建 StoryKernel 时 payload 变成 Map.of()
P6/QC 重建 StoryKernel 时 entities/events/worldStates 为空
QC 使用 text.contains(targetKey) 判断义务是否兑现
义务证据模型没有统一定义
RomanceProfile 尚未接入生产链
真实悬疑/言情探针未执行
```

因此本文档不是从零开始的设计，而是对已有实现的“补齐和纠偏设计”。

## 2. 目标与非目标

### 2.1 目标

本次实现必须达到：

```text
一个通用故事核心
一个类型插件接口
一套可执行叙事义务
一套统一义务证据模型
⑥b 窗口级确定性校验
P6 通用审计 + 类型审计
QC 通用审计 + 类型审计
旧悬疑 run 兼容
真实探针可复现
```

### 2.2 非目标

本次不做：

- 删除旧 `StoryContract`；
- 删除旧 P6/QC；
- 重写采样算法；
- 重写正文生成算法；
- 把 SPLICE 变成生产主链；
- 一次性支持所有小说类型；
- 未经用户授权 commit、push、部署；
- 仅靠提示词解决所有语义问题。

## 3. 现有实现基线

### 3.1 当前主链

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
   HEAD
   BATCH
→ P6 立项闸
→ ⑦ 正文
→ ⑧ QC
→ PASSED / REWORK / FAILED
```

### 3.2 当前分支和迁移基线

当前工作区分支：

```text
codex/derive-route-contract-and-legacy-stability-20261031
```

当前已存在并已应用的新迁移：

```text
V143__derive_genre_metadata.sql
V144__derive_story_obligation.sql
```

本次设计新增的迁移：

```text
V145__derive_story_obligation_evidence.sql
V146__derive_genre_contract.sql
```

线上最后一次确认的 schema：

```text
derive schema = 144
```

### 3.3 必须保留的旧路径

旧 `StoryContract`、旧 `ChapterScheduleEntry`、旧 ROUTE parser、旧 P6/QC 读路径必须保留。

旧 run 没有 `genre` 字段时必须默认：

```text
genre = MYSTERY
```

### 3.4 必须解决的合同类型冲突

当前已实现的 `GenreProfile` 接口错误地把所有类型合同都定义为：

```text
StoryContract parseContract(...)
```

但 `StoryContract` 包含：

```text
truth
culpritKey
culpritName
motive
method
clues
revealChapter
```

这些字段属于悬疑推理，不属于言情、都市或奇幻。因此不能要求 `RomanceProfile` 返回一个不含这些字段、却仍强制实现 `StoryContract` 的对象。

正确模型必须泛型化：

```text
GenreProfile<C extends GenreContract>
```

其中：

```text
MysteryContract 包装旧 StoryContract
RomanceContract 只包含言情合同
FutureGenreContract 只包含对应类型合同
```

这不是实现 AI 的能力问题，而是当前接口设计错误。

## 4. 总体架构

目标链路：

```text
采样
→ GenreProfile 生成类型合同
→ 旧 StoryContract 兼容层冻结类型合同
→ StoryKernel 投影
→ StoryObligation 投影
→ derive_story_obligation 持久化
→ ⑥b BATCH 输出
→ DraftWindowObligationValidator
→ 窗口级错误回喂/局部回炉
→ P6 通用审计 + 类型审计
→ ⑦ 正文
→ QC 通用审计 + 类型审计
```

核心原则：

```text
结构字段由 Java 决定
叙事表达由模型决定
类型专属字段只存在于 GenreProfile
义务必须有证据，不能只靠字符串包含
旧 run 永远不能因为新表缺失而不可读
```

## 5. 通用故事核心 V2

### 5.1 StoryKernel

`StoryKernel` 是跨题材共享的运行时投影，不直接替代旧合同。

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
evidenceIndex
```

约束：

- `entities` 的 key 唯一；
- `obligations` 的 key 唯一；
- `events` 的 key 唯一；
- `threads` 的 key 唯一；
- `status` 只允许 `ACTIVE`、`LEGACY`、`DRAFT`；
- `scope` 可以为 null，但 P6/QC 不得依赖 null scope 做章号判断；
- 不得出现悬疑专属字段。

### 5.2 StoryEntity

```text
key
kind
name
attributes
introducedChapter
```

允许的通用 kind：

```text
CHARACTER
ORGANIZATION
LOCATION
ITEM
PHENOMENON
```

角色功能由插件提供，不由通用实体硬编码。

### 5.3 StoryThread

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

通用状态机：

```text
OPEN → ECHOED → PAYOFF → CLOSED
```

允许的通用 kind：

```text
CLUE
RELATIONSHIP
OBJECT
GOAL
POWER
CONTRACT
```

### 5.4 StoryEvent

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

允许的通用 kind：

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

### 5.5 StoryObligation

这是本次设计的核心抽象。

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

固定 key 格式：

```text
CH{chapterNo}:{kind}:{targetKey}
```

允许的义务 kind：

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

### 5.6 StoryObligationEvidence

义务不能通过正文是否包含 `targetKey` 来判断。必须新增统一证据模型。

```text
obligationKey
chapterNo
evidenceKind
eventKey
beatSeq
entityKeys
threadKeys
fact
source
confidence
```

允许的 evidenceKind：

```text
CHAPTER_OUTLINE
BEAT_FILLED
EVENT
KNOWLEDGE_EVENT
STATE_CHANGE
THREAD_EFFECT
TEXT_SPAN
```

规则：

- `PLANT_THREAD` 至少需要一个 `THREAD_EFFECT` 或 `BEAT_FILLED` 证据；
- `PAYOFF_THREAD` 至少需要一个 `THREAD_EFFECT` 或 `BEAT_FILLED` 证据；
- `CHARACTER_CHOICE` 至少需要一个 `EVENT` 或 `CHAPTER_OUTLINE` 证据；
- `CHARACTER_COST` 必须有前一个 `CHARACTER_CHOICE` 的依赖证据；
- `FINAL_CLOSURE` 必须有 `EVENT` 或明确的 `TEXT_SPAN` 证据；
- 证据里的 chapterNo 必须等于义务 chapterNo；
- 禁止把义务 key 本身当成正文证据。

### 5.7 StoryEvidenceIndex

为窗口校验和 QC 提供统一查询：

```text
eventsByChapter
beatsByChapter
knowledgeByChapter
stateChangesByChapter
threadEffectsByChapter
textSpansByChapter
```

## 6. 类型插件 V2

### 6.1 GenreProfile

接口必须明确区分：

```text
类型合同解析
类型合同校验
类型义务投影
类型证据校验
类型 P6 审计
类型 QC 审计
```

正确接口必须泛型化：

```text
public interface GenreContract {
}

public interface GenreProfile<C extends GenreContract> {
    String genre();
    C parseContract(JsonNode root, ScopeContract scope);
    List<PlanIssue> validateContract(C contract);
    List<StoryObligation> projectObligations(
        C contract,
        List<ChapterScheduleEntry> schedule);
    List<PlanIssue> auditGate(
        C contract,
        StoryKernel kernel,
        StoryEvidenceIndex evidence);
    List<PlanIssue> auditQc(
        C contract,
        StoryKernel kernel,
        StoryEvidenceIndex evidence,
        CompiledDraft draft);
}
```

`GenreContractCodec<C>` 负责类型合同的 JSON 持久化：

```text
String genre()
JsonNode encode(C contract)
C decode(JsonNode payload, ScopeContract scope)
```

注册表保存 `GenreProfile<?>`，调用方必须通过对应 codec 解码，禁止把 `RomanceContract` 强转为 `StoryContract`。

### 6.2 MysteryProfile

悬疑专属字段：

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

投影：

```text
clue.plantedChapter → PLANT_THREAD
clue.payoffChapter → PAYOFF_THREAD
truth.revealChapter → FINAL_CLOSURE
```

悬疑 QC 不能只检查正文包含 `GM-CLUE-xx`。必须检查：

```text
线程效果事件是否存在
线索 payoff 事件是否存在
真相三要素是否在 reveal 章出现
reveal 前是否出现 solution 级信息
```

### 6.3 RomanceProfile

`RomanceProfile` 是第二个必须接入生产链的插件，用来证明核心不是悬疑专用。

#### 6.3.1 RomanceContract 的 Java 结构

`RomanceContract` 是独立合同，不得继承或伪装成 `StoryContract`。

```text
RomanceContract(
    int version,
    ScopeContract scope,
    List<RomanceCharacterSlot> cast,
    RomanceRelationship primaryRelationship,
    Map<RomanceStageKind, RomanceStage> stages,
    List<String> forbidden)

RomanceCharacterSlot(
    String key,
    String name,
    String role,
    String gender,
    String identity)

RomanceRelationship(
    String threadKey,
    String leftKey,
    String rightKey,
    String kind,
    String title)

RomanceStage(
    RomanceStageKind kind,
    int chapterNo,
    String targetKey,
    List<String> actorKeys,
    List<String> threadRefs,
    boolean required,
    String proof,
    Map<String,Object> payload)

RomanceStageKind:
  RELATIONSHIP_START
  MISUNDERSTANDING
  ATTACHMENT_ESCALATION
  CONFLICT_PEAK
  CONFESSION
  RECONCILIATION
  RELATIONSHIP_CLOSURE
```

`RomanceCharacterSlot.role` 只允许：

```text
PROTAGONIST
LOVE_INTEREST
RIVAL
FRIEND
FAMILY
SUPPORT
OPPOSITION
```

`RomanceRelationship.kind` 只允许：

```text
ROMANTIC
FRIENDSHIP
FAMILY
RIVALRY
```

`RomanceStage.targetKey` 的含义由 stage 决定：

```text
RELATIONSHIP_START / ATTACHMENT_ESCALATION / CONFESSION /
RECONCILIATION / RELATIONSHIP_CLOSURE
  → primaryRelationship.threadKey

MISUNDERSTANDING / CONFLICT_PEAK
  → 触发选择或代价的 characterKey
```

`RomanceStage.actorKeys` 必须来自 `cast`；`threadRefs` 必须包含 `primaryRelationship.threadKey`，除非 stage 明确是纯角色内部选择。

#### 6.3.2 RomanceContract 的 JSON 形状

`RomanceCodec` 必须按以下形状编码和解码：

```json
{
  "cast": [
    {"key":"C1","name":"林晚","role":"PROTAGONIST","gender":"女","identity":"修复师"},
    {"key":"C2","name":"沈砚","role":"LOVE_INTEREST","gender":"男","identity":"旧书店店主"}
  ],
  "primaryRelationship": {
    "threadKey":"REL-C1-C2",
    "leftKey":"C1",
    "rightKey":"C2",
    "kind":"ROMANTIC",
    "title":"修复师与旧书店店主的关系线"
  },
  "stages": {
    "RELATIONSHIP_START": {
      "chapterNo":1,
      "targetKey":"REL-C1-C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"两人因修复委托第一次合作",
      "payload":{}
    },
    "MISUNDERSTANDING": {
      "chapterNo":2,
      "targetKey":"C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"林晚误以为沈砚隐瞒旧书来源",
      "payload":{}
    },
    "ATTACHMENT_ESCALATION": {
      "chapterNo":3,
      "targetKey":"REL-C1-C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"两人共同修复同一本书并逐渐信任",
      "payload":{}
    },
    "CONFLICT_PEAK": {
      "chapterNo":4,
      "targetKey":"C1",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"林晚必须在旧书归属和沈砚之间做选择",
      "payload":{}
    },
    "CONFESSION": {
      "chapterNo":5,
      "targetKey":"REL-C1-C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"沈砚说明隐瞒原因并表达感情",
      "payload":{}
    },
    "RECONCILIATION": {
      "chapterNo":6,
      "targetKey":"REL-C1-C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"误会解除，两人重新合作",
      "payload":{}
    },
    "RELATIONSHIP_CLOSURE": {
      "chapterNo":7,
      "targetKey":"REL-C1-C2",
      "actorKeys":["C1","C2"],
      "threadRefs":["REL-C1-C2"],
      "required":true,
      "proof":"关系选择产生明确结果",
      "payload":{}
    }
  },
  "forbidden":["不得使用凶手/作案手法等悬疑合同字段"]
}
```

#### 6.3.3 RomanceContract 校验

`RomanceCodec.decode` 和 `RomanceProfile.validateContract` 必须校验：

```text
cast 非空且 key 唯一
primaryRelationship.leftKey/rightKey 都属于 cast
threadKey 必须等于 REL-{leftKey}-{rightKey}
每个 stage 的 chapterNo 在 1..scope.chapterCount
stage 章号必须单调不减
RELATIONSHIP_START.chapterNo 必须是所有阶段中最早
RELATIONSHIP_CLOSURE.chapterNo 必须是最后一个阶段
CONFESSION 不得早于 ATTACHMENT_ESCALATION
RECONCILIATION 不得早于 CONFLICT_PEAK
actorKeys 必须属于 cast
threadRefs 必须包含 primaryRelationship.threadKey
forbidden 不得出现悬疑专属字段
```

如果合同缺少任一 required stage，直接返回：

```text
GENRE_CONTRACT_INVALID
missingStage=...
```

#### 6.3.4 RomanceContract 的义务投影

投影到通用义务：

```text
RELATIONSHIP_START → ADVANCE_THREAD
MISUNDERSTANDING → CHARACTER_CHOICE
ATTACHMENT_ESCALATION → CHANGE_STATE
CONFLICT_PEAK → CHARACTER_COST
CONFESSION → PAYOFF_THREAD
RECONCILIATION → LOCAL_CLOSURE
RELATIONSHIP_CLOSURE → FINAL_CLOSURE
```

义务 key 仍使用统一格式：

```text
CH{chapterNo}:{kind}:{targetKey}
```

义务 payload 必须保留：

```text
stage
threadKey
actorKeys
proof
relationshipKind
```

#### 6.3.5 RomanceContract 与主链的接入

`SCHEDULE`：

```text
RomanceContract 投影出义务
derive_story_thread 增加 primaryRelationship
ChapterScheduleEntry.mustPlant/mustPayoff 保持空
```

`SLOT_ROUTE`：

```text
关系线 threadKey 进入合法 threadKeys
义务投影为 SYNTHETIC 拍
关系线拍使用 REL-C1-C2 等通用关系线引用
不得生成 CLUE_PLANT/CLUE_PAYOFF
```

义务到 `BeatRole` 的映射：

```text
ADVANCE_THREAD → MAIN_ADVANCE
CHARACTER_CHOICE → CHARACTER_CHOICE
CHANGE_STATE → MAIN_ADVANCE
CHARACTER_COST → COST
PAYOFF_THREAD → RELATION_CONFLICT
LOCAL_CLOSURE → LOCAL_CLOSURE
FINAL_CLOSURE → FINAL_CLOSURE
```

`⑥b`：

```text
输入必须包含 RomanceContract stages
输出新增 romanceEvents
romanceEvents[].stage 必须来自 RomanceStageKind
romanceEvents[].evidence 必须引用 stage.targetKey/threadKey
不得输出 clueEvents/closingActs
```

`P6`：

```text
RomanceGateAuditor 检查关系线开闭
检查阶段顺序
检查误解、冲突、告白、和好、闭合是否有证据
不得调用 MysteryGateAuditor
```

`QC`：

```text
RomanceQcAuditor 检查关系状态变化和阶段证据
不得使用 text.contains(threadKey)
不得检查凶手/动机/手法
```

### 6.4 合同持久化

新增 `derive_genre_contract`：

```text
run_id
genre
contract_version
scope
payload
status
```

`payload` 是 JSONB，由对应 `GenreContractCodec` 编码。

旧悬疑 run 的读取方式：

```text
读取 derive_story_contract
→ MysteryCodec.decode
→ MysteryContract
```

新悬疑 run 的写入方式：

```text
写 derive_genre_contract
→ 必要时兼容投影 derive_story_contract
```

言情 run 的写入方式：

```text
只写 derive_genre_contract
不得伪造 truth/clues/culprit
```

## 7. 合同与日程流程

### 7.1 SCALE

保持现有：

```text
章数
卷数
总字数带
```

输出仍为 `ScopeContract`。

### 7.2 CONTRACT

流程：

```text
读取 run.genre
→ registry.require(genre)
→ profile.parseContract
→ profile.validateContract
→ GenreContractCodec.encode
→ 冻结 derive_genre_contract
→ 悬疑类型可兼容投影 derive_story_contract
→ 生成 StoryKernel 投影
```

`run.genre` 缺失时旧 run 兼容为 `MYSTERY`；新 run 必须显式或默认写入 `MYSTERY`。

### 7.3 SCHEDULE

`ChapterScheduleEntry` 保留旧字段：

```text
chapterNo
volumeNo
reveal
mustPlant
mustPayoff
```

新增义务持久化：

```text
profile.projectObligations
→ StoryObligationProjector.project
→ derive_story_obligation
```

投影必须满足：

```text
义务 key 唯一
义务章号必须在日程内
同一线索不能出现两个同章 PAYOFF
角色 setup 必须投影到合法章节
卷闭合必须投影到卷末章
```

### 7.4 SLOT_ROUTE

路由仍由 Java 决定：

```text
章号
拍 seq
拍角色
依赖
知识
状态
线程
```

ROUTE 提示词、schema、Java parser 必须保持同一套字段，不得再次出现：

```text
模型输出 beatNarratives
Java parser 要 volumeNo
```

## 8. 义务持久化

### 8.1 derive_story_obligation

保留 V144 表，但必须补证据关联能力：

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

新增或扩展：

```text
evidence_status
last_error
repair_round
```

状态：

```text
PLANNED
FULFILLED
VIOLATED
SKIPPED
```

### 8.2 payload 必须保留

以下 payload 不能在 P6/QC 重建义务时丢弃：

```text
setup
proof
afterChapter
clueKey
phase
sourceContract
characterKey
relationshipKey
worldRuleKey
```

P6/QC 读取义务时必须从数据库恢复 payload，不能使用 `Map.of()`。

## 9. ⑥b 窗口校验 V2

### 9.1 输入

每个 BATCH 窗口输入：

```text
windowFrom
windowTo
chapters
beats
clueActs
stateChanges
knowledgeEvents
threadEffects
allowedEntities
allowedThreads
obligations
```

### 9.2 输出证据

模型输出的结构化字段必须转换为：

```text
StoryEvidenceIndex
```

不能只保存自由文本。

### 9.3 校验规则

#### 规则 1：义务缺失

`required=true` 的义务在本窗内没有证据：

```text
OBLIGATION_MISSING
```

#### 规则 2：义务提前

`PAYOFF_THREAD` 在 payoff 章之前出现：

```text
OBLIGATION_EARLY
```

#### 规则 3：义务重复

同一 payoff 在本窗出现多次：

```text
OBLIGATION_VIOLATED
```

#### 规则 4：状态冲突

同一实体同一状态在同一窗出现互斥值：

```text
STATE_CONFLICT
```

#### 规则 5：知识来源缺失

知识 READ 早于 WRITE：

```text
KNOWLEDGE_SOURCE_MISSING
```

#### 规则 6：合同越界

输出引用合同外实体、线程、角色：

```text
CONTRACT_OUT_OF_SCOPE
```

#### 规则 7：铺垫缺失

`CHARACTER_COST` 或 `CHARACTER_CHOICE` 没有前文 setup：

```text
OBLIGATION_MISSING_SETUP
```

#### 规则 8：提前揭示

reveal 章之前出现真相三要素：

```text
GENRE_REVEAL_LEAK
```

### 9.4 回炉范围

窗口校验失败时：

```text
只重做当前 BATCH 窗口
不默认清空整本书
不删除已完成窗口
错误必须带 chapterNo/obligationKey/evidencePath
```

## 10. P6 V2

P6 分成两层。

### 10.1 通用 P6

检查：

```text
义务是否完成
线程是否闭合
状态是否冲突
知识来源是否成立
FINAL_CLOSURE 是否存在
```

必须读取：

```text
StoryKernel
StoryObligation
StoryEvidenceIndex
```

不能构造空 `StoryKernel`。

### 10.2 类型 P6

MysteryProfile 检查：

```text
clue plant/payoff
truth reveal
solution 提前泄露
culprit/motive/method 一致性
```

RomanceProfile 检查：

```text
关系线开闭
误会是否升级
告白/和好是否兑现
关系结局是否闭合
```

### 10.3 P6 错误输出

必须是结构化：

```text
code
chapterNo
obligationKey
eventKey
threadKey
message
repairTarget
```

禁止只返回一长串不可定位的中文描述。

## 11. QC V2

### 11.1 通用 QC

检查正文：

```text
章纲是否兑现
义务证据是否在正文/事件中出现
世界状态是否冲突
时间线是否冲突
线程是否闭合
```

### 11.2 类型 QC

MysteryProfile 检查：

```text
线索是否在正确章兑现
reveal 前是否泄底
真相三要素是否在正文中兑现
```

RomanceProfile 检查：

```text
关系状态是否按日程变化
confession/reconciliation 是否兑现
是否提前完成关系闭合
```

### 11.3 QC 证据禁止事项

禁止：

```java
text.contains("GM-CLUE-01")
```

禁止：

```java
text.contains("TRUTH")
```

义务兑现必须来自 `StoryEvidenceIndex` 或明确的结构化事件。

## 12. 旧 run 兼容

旧 run 的读取路径：

```text
无 genre → MYSTERY
无 obligation 行 → 使用 LegacyMysteryAdapter 投影
有旧 STORY_CONTRACT 快照 → 读取旧合同
有旧 CHAPTER_SCHEDULE → 投影义务
无新表 → 不影响详情读取
```

旧 run 不强制写入 V143/V144；只有新 run 才要求：

```text
genre
story_kernel_version
genre_contract_version
derive_story_obligation
```

## 13. 错误模型

统一错误码：

```text
MODEL_OUTPUT_INVALID
OBLIGATION_MISSING
OBLIGATION_EARLY
OBLIGATION_VIOLATED
OBLIGATION_MISSING_SETUP
STATE_CONFLICT
KNOWLEDGE_SOURCE_MISSING
CONTRACT_OUT_OF_SCOPE
GENRE_REVEAL_LEAK
GENRE_CONTRACT_INVALID
LEGACY_COMPAT_ERROR
```

每个错误必须能定位到：

```text
runId
chapterNo
volumeNo
obligationKey
eventKey
threadKey
source
```

## 14. 数据迁移

### 14.1 V143

已有：

```text
run.genre
run.story_kernel_version
run.genre_contract_version
```

实现时必须确认：

```text
RunDO
RunCreateDTO
DeriveRunMapper.xml
DeriveSampleService
```

全部同步。

### 14.2 V144

已有：

```text
derive_story_obligation
```

实现时必须确认：

```text
DeriveStoryObligationDO
DeriveStoryObligationMapper
DeriveStoryObligationMapper.xml
```

全部同步。

### 14.3 V145

如果实现证据索引，新增：

```text
derive_story_obligation_evidence
```

字段：

```text
id
run_id
obligation_key
chapter_no
evidence_kind
event_key
beat_seq
entity_keys
thread_keys
fact
source
confidence
created_time
updated_time
```

唯一约束：

```text
(run_id, obligation_key, evidence_kind, chapter_no, event_key)
```

## 15. 测试要求

### 15.1 通用核心

```text
StoryKernel key 唯一
StoryObligation key 唯一
StoryEvidenceIndex 可查询
StoryConstraint 范围合法
StoryClosure 必填字段合法
```

### 15.2 插件

Mystery：

```text
culprit 属于 cast
线索 plant/payoff 章合法
reveal 不早于最晚 plant
真相三要素可审计
```

Romance：

```text
无 culprit/motive/method/revealChapter
关系线可开闭
confession/reconciliation 可投影为义务
```

### 15.3 义务投影

```text
悬疑合同 → PLANT/PAYOFF/FINAL_CLOSURE
角色合同 → INTRODUCE/CHOICE/COST
卷合同 → LOCAL_CLOSURE/FINAL_CLOSURE
日程 → 章号合法义务
重复 key → 失败
```

### 15.4 ⑥b 窗口

```text
提前 payoff
重复 payoff
缺 setup
缺义务
提前 reveal
状态冲突
知识来源缺失
合同外实体
```

### 15.5 P6/QC

必须测试：

```text
P6 不构造空 StoryKernel
P6 读取 obligation payload
QC 不使用 text.contains(targetKey)
QC 使用证据索引
旧 run 无新表仍可读取
```

### 15.6 真实探针

必须跑：

```text
悬疑 4 章
言情 4 章
```

每个探针必须产出：

```text
runId
genre
合同版本
义务数量
义务证据数量
窗口校验结果
P6 结果
正文章数
QC 结果
失败原因
```

## 16. 实施阶段

### 阶段 A：补齐证据模型

交付：

```text
StoryObligationEvidence
StoryEvidenceIndex
义务读写接口
```

验收：

```text
义务 payload 不再丢失
证据可以按章查询
```

### 阶段 B：修 ⑥b 校验

交付：

```text
窗口证据抽取
义务证据校验
局部回炉
```

验收：

```text
提前 payoff 在 P6 前被拦截
重复 payoff 在 P6 前被拦截
缺 setup 在 P6 前被拦截
```

### 阶段 C：修 P6

交付：

```text
完整 StoryKernel 装配
payload 恢复
通用审计
类型审计
```

验收：

```text
P6 能定位义务 key 和章号
P6 不再依赖空 kernel
```

### 阶段 D：修 QC

交付：

```text
正文证据索引
世界状态索引
线程效果索引
类型 QC
```

验收：

```text
QC 不再用 targetKey 字符串包含
QC 能判断真实线索兑现
QC 能判断真实关系闭合
```

### 阶段 E：接入 RomanceProfile

交付：

```text
RomanceProfile 合同
RomanceProfile 义务投影
RomanceProfile P6
RomanceProfile QC
```

验收：

```text
言情 4 章探针完成
核心链不出现悬疑字段
```

### 阶段 F：真实探针

交付：

```text
悬疑探针报告
言情探针报告
失败日志
```

验收：

```text
探针报告可复现
没有未解释的卡死
没有未解释的空 kernel
没有未解释的字符串包含误杀
```

## 17. 实现 AI 的执行规则

1. 先读取本文档和所有列出的代码。
2. 不得直接删除旧合同或旧路径。
3. 不得只让单测通过就宣称完成。
4. 每个阶段必须有真实数据路径测试。
5. 不得把 payload 重建成 `Map.of()`。
6. 不得用 `text.contains(targetKey)` 判断义务。
7. 不得构造空 `StoryKernel` 后交给 P6/QC。
8. 不得未经用户授权 commit、push、部署。
9. 每阶段运行：

```text
mvn -q test
mvn -q -DskipTests package
git diff --check
```

10. 遇到设计与代码冲突时，停下来报告，不要自行猜测。

## 18. 完成标准

只有满足以下条件，才能说实现完成：

- 通用核心无悬疑专属字段；
- MysteryProfile 和 RomanceProfile 都接入生产链；
- 义务 payload 全链路保留；
- 义务证据模型真实存在；
- ⑥b 能在 P6 前拦截提前/重复/缺 setup/状态冲突；
- P6 使用完整 StoryKernel；
- QC 不再使用字符串包含判断义务；
- 旧 run 无新表仍可读取；
- 悬疑 4 章探针完成；
- 言情 4 章探针完成；
- `mvn -q test` 通过；
- `mvn -q -DskipTests package` 通过；
- `git diff --check` 通过；
- 没有未经授权的 commit、push、部署。

## 19. 针对宝塔最新 run 的具体修复方案

本节依据 2026-10-31 宝塔 `derive_gen` 的最新实测，不使用假设数据。

### 19.1 run65 的实际过程

run65 的过程日志显示：

```text
采样完成
→ 冻结故事合同
→ 章节日程完成
→ 拍路由完成
→ ⑥b HEAD 完成
→ 窗1：第1-3章完成
→ 窗2：第4章完成
→ 第5章义务校验失败
```

失败错误：

```text
OBLIGATION_WINDOW
OBLIGATION_MISSING
CH5:FINAL_CLOSURE:TRUTH
```

数据库中已经存在第5章义务：

```text
CH5:PAYOFF_THREAD:GM-CLUE-01
CH5:PAYOFF_THREAD:GM-CLUE-02
CH5:PAYOFF_THREAD:GM-CLUE-03
CH5:PAYOFF_THREAD:GM-CLUE-04
CH5:FINAL_CLOSURE:TRUTH
```

第5章正文和章纲为空，P6 和 QC 尚未执行。

run65 还曾出现：

```text
CONTRACT_CLOSURE_DRIFT
揭示章第5章 draft_outline 必须逐字写出 motiveTag
```

这说明当前系统把“合同事实”转换成了对自然语言原文的逐字要求。该要求过脆：

- 模型可能表达正确但不逐字包含标签；
- 模型可能逐字包含标签但叙事证据错误；
- 同一窗没有揭示章时，未来章节义务不应被当前窗提前拒绝。

### 19.2 run64 的实际过程

run64 的 P6 第一轮返回：

```text
REWORK
repairTarget=PLAN_VOLUME:1
```

P6 指出的真实问题包括：

```text
第2章提前泄露第3章的打卡记录
第2章提前收束“排除外盗”
第4章认罪触发点没有前文铺垫
同一锁具事实重复多次
线索 payoff 与日程不一致
```

第二轮 P6 变为：

```text
GO
PASS
issues=0
```

但 run64 最终仍失败，因为 QC 的总字数硬判失败：

```text
总字数 7329
要求范围 3000~5000
qc_round=3
run.status=FAILED
```

这证明：

```text
P6 通过 ≠ 字数预算通过
P6 通过 ≠ 可以交付
义务合同本身不能替代正文长度控制
```

### 19.3 run65 的结构性修复：闭合义务必须可机器执行

新增通用闭合合同：

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

悬疑插件投影：

```text
truth.revealChapter → closureKey=TRUTH
requiredActs=[CULPRIT, MOTIVE, METHOD]
requiredTags=[culpritName, motiveTag, methodTag]
proofEventKeys=合同线索 keys
```

⑥b 的揭示章输出必须提供结构化字段：

```json
{
  "closingActs": ["CULPRIT", "MOTIVE", "METHOD"],
  "closureEvidence": {
    "culpritKey": "C3",
    "motiveTag": "灭口护赝品调包生意",
    "methodTag": "封蜡切口重压嫁祸钥匙人",
    "proofEventKeys": ["GM-CLUE-01", "GM-CLUE-02"]
  }
}
```

Java 校验不再要求自然语言逐字包含标签，而是校验：

```text
closingActs 是否覆盖 CULPRIT/MOTIVE/METHOD
closureEvidence.culpritKey 是否属于合同 cast
closureEvidence.motiveTag 是否等于冻结合同
closureEvidence.methodTag 是否等于冻结合同
proofEventKeys 是否都是合同线索
```

如果揭示章不在当前 BATCH 窗口，窗口校验必须：

```text
把该义务标记为 DEFERRED
不得报 OBLIGATION_MISSING
在全部窗口完成后执行全局 closure gate
```

如果揭示章在当前窗口，缺失闭合字段时必须返回：

```text
CLOSURE_WINDOW_MISSING
chapterNo=5
obligationKey=CH5:FINAL_CLOSURE:TRUTH
missingActs=[CULPRIT,MOTIVE,METHOD]
repairTarget=WINDOW[3]
```

而不是泛化成“缺少义务”。

### 19.4 run65 的窗口边界修复

新增窗口义务作用域：

```text
WindowObligationScope
```

字段：

```text
windowFrom
windowTo
currentObligations
deferredObligations
futureObligations
```

规则：

```text
义务章号在窗口内 → 当前校验
义务章号在窗口后 → deferred
义务章号在窗口前且未完成 → 已失败，不得静默跳过
```

这样 run65 的第5章义务不会在第4章窗口提前报错；但第5章窗口必须在写入前完成闭合校验。

### 19.5 run65 的定向回炉修复

窗口失败时必须生成结构化修复指令：

```text
errorCode
windowNo
chapterNo
obligationKey
missingFields
contractPayload
previousOutputPath
repairTarget
```

示例：

```text
errorCode=CLOSURE_WINDOW_MISSING
windowNo=3
chapterNo=5
obligationKey=CH5:FINAL_CLOSURE:TRUTH
missingActs=[CULPRIT,MOTIVE,METHOD]
contractPayload={
  "culpritKey":"C3",
  "motiveTag":"灭口护赝品调包生意",
  "methodTag":"封蜡切口重压嫁祸钥匙人"
}
repairTarget=WINDOW[3]
```

模型重试时只收到：

```text
当前窗口
缺失义务
合同事实
输出结构
```

不得把整个 run 清空，也不得重新生成前面已经通过的窗口。

### 19.6 run64 的结构问题修复

run64 的问题应由义务证据链在 ⑥b 阶段拦截。

#### 问题 1：提前 payoff

合同规定：

```text
GM-CLUE-03 payoffChapter=3
```

第2章模型却写出打卡记录已经到手。

修复校验：

```text
threadEffect:
  threadKey=GM-CLUE-03
  effect=PAYOFF
  chapter=2
```

应返回：

```text
OBLIGATION_EARLY
chapterNo=2
obligationKey=CH3:PAYOFF_THREAD:GM-CLUE-03
evidenceKind=THREAD_EFFECT
```

#### 问题 2：重复收束

合同规定：

```text
GM-CLUE-01 payoffChapter=3
```

第2章和第3章都写“排除外盗”结论。

修复校验：

```text
同一 targetKey 的 PAYOFF_THREAD
在整本书只能有一次 ful evidence
第二次出现返回 OBLIGATION_VIOLATED
```

#### 问题 3：动机无铺垫

角色合同如果要求：

```text
requiredSetup=[旧史铺垫]
highlightCost=旧史被公开
```

则第4章不能凭空出现旧史触发点。

投影义务：

```text
CH{setupChapter}:INTRODUCE_ENTITY:C3
CH{choiceChapter}:CHARACTER_CHOICE:C3
CH{costChapter}:CHARACTER_COST:C3
```

窗口校验必须检查：

```text
CHARACTER_COST 的 afterChapter 之前
必须存在同 targetKey 的 INTRODUCE_ENTITY
且存在对应 CHARACTER_CHOICE 证据
```

否则返回：

```text
OBLIGATION_MISSING_SETUP
chapterNo=4
entityKey=C3
missingSetup=旧史铺垫
```

#### 问题 4：事实重复

新增事实指纹：

```text
factKey
chapterNo
factText
sourceEventKey
```

事实指纹不使用全文相似度作为唯一判据，而使用：

```text
合同 clue key
beat seq
event key
state change
knowledge event
```

同一 `factKey` 在同一卷内重复完整解释时返回：

```text
FACT_DUPLICATE
chapterNo
factKey
previousChapterNo
```

### 19.7 run64 的字数失败修复

新增：

```text
WordBudgetGuard
```

输入：

```text
run.wordsMin
run.wordsMax
scope.chapterCount
scope.chapterAvg
window.charCount
total.charCount
remainingChapters
```

窗口写入前计算：

```text
projectedMin = total + remainingChapters * chapterAvg * 0.9
projectedMax = total + remainingChapters * chapterAvg * 1.1
```

如果：

```text
total > wordsMax
```

立即返回：

```text
BUDGET_WINDOW_OVERSHOOT
windowNo
chapterNo
totalCharCount
wordsMax
repairTarget=COMPRESS_WINDOW
```

如果：

```text
projectedMax < wordsMin
```

返回：

```text
BUDGET_WINDOW_UNDERSHOOT
windowNo
chapterNo
projectedMax
wordsMin
repairTarget=EXPAND_WINDOW
```

如果：

```text
total <= wordsMax
但 projectedMax 明显超过 wordsMax
```

返回：

```text
BUDGET_PROJECTED_OVERSHOOT
windowNo
projectedMax
wordsMax
repairTarget=COMPRESS_REMAINING_WINDOWS
```

这能把 run64 的 7329/5000 失败从“全书写完后 QC 才发现”提前到：

```text
某一个窗口写完后就停止
→ 定向压缩当前窗或剩余窗
→ 不继续浪费后续 token
```

### 19.8 direct=true 的语义修复

run65 使用了：

```text
direct=true
```

当前 direct 语义是跳过 P6 和 AI QC。V2 必须明确：

```text
direct=true 只跳过 LLM 语义审稿
不得跳过确定性合同、义务、闭合、预算和证据校验
```

direct 模式下仍必须执行：

```text
StoryContractValidator
StoryObligationProjector
DraftWindowObligationValidator
ClosureValidator
WordBudgetGuard
script QC
```

如果 direct=true 仍允许跳过这些校验，则 run65 的闭合缺失会继续以“跳过 P6”的方式进入后续阶段。

### 19.9 run64/run65 的验收标准

部署 V2 后，至少需要证明：

```text
run65 类型的第5章闭合缺失
→ 在第5章窗口被拦截
→ 错误定位到 CH5:FINAL_CLOSURE:TRUTH
→ 只重做第5章窗口
→ 不清空第1-4章

run64 类型的提前 payoff
→ 在第2章窗口被拦截
→ 错误定位到 GM-CLUE-03
→ 不进入 P6 才发现

run64 类型的 7329/5000 字数失败
→ 在预算超限的窗口被拦截
→ 返回 BUDGET_WINDOW_OVERSHOOT
→ 不进入三轮 QC 后才失败
```

## 20. 最终判断

当前系统不是“没有合同”，而是：

```text
合同不够通用
义务不够可执行
证据模型缺失
P6/QC 装配不完整
真实探针未完成
```

本次实现不能继续以“新增几个类、单测通过”为完成标准。必须把：

```text
合同
义务
证据
窗口校验
P6
QC
真实探针
```

串成一条真正可运行、可定位、可回炉的生产链。
