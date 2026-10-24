# 随机衍生链路稳定性修复计划 v1

> 依据：run 39、run 40 实跑记录、现有 `derive_gen` 表结构、`docs/STATUS.md` 与当前代码模块边界。  
> 目标：把随机衍生链路从“模型自觉 + 下游报错 + 整段回炉”升级为“结构程序化、语义模型化、错误可分类、阶段可回放”。

---

## 0. 数据库备份已完成

备份目录：

```text
E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\var\db-backup-20261024-144541
```

包含：

- `derive_gen-involved.dump`
  - `pg_dump -F c`
  - 结构 + 数据
  - SHA256：
    `00BF2B842C5CD1E373EA2940A1B2213AB1097FB46C0C8223E74E036160EE80C4`
- `derive_gen-involved.sql`
  - `pg_dump -F p`
  - 结构 + 数据
  - SHA256：
    `BE5D6912403787E6DCC1D4ED2C75583D35AA624DE9105EC672F98318DD379A8E`
- `derive_gen-involved.restore-list.txt`
- `MANIFEST.txt`

已备份表：

```text
run
derive_volume_plan
derive_story_thread
derive_character_plan
beat_instance
chapters
novels
draft_history
qc_report
world_states
llm_call_log
asset_outline
asset_beat
asset_world
label_index
domain_cell
wordlist
source_chapter
calibration
work_fingerprint
flyway_schema_history
```

注意：这些表存在外键关系，实际恢复时应在完整 `derive_gen` 环境中恢复，或至少保证 `users` 等被外键引用的父表存在。

---

## 1. 当前问题总览

### 1.1 已修复的问题

1. 前端停止态和轮询错误展示。
2. 跳过拍 `chapter_no=null` 导致的 NPE。
3. ⑥b `filled/skipped` 冲突。
4. ⑥a forced-skip 被模型重新分配。
5. synthetic 补拍丢失知识/状态字段。

### 1.2 仍存在的结构性问题

run 40 暴露出：

1. 模型输出整体 JSON 时容易漏字段；
2. 真相卡、主线、章纲、人物表之间没有统一的事实账；
3. 线程埋设/回声/兑现没有被程序强制补齐；
4. 知识因果依赖自由文本，模型写出的“前置知识”可能没有更早的写入来源；
5. P6 发现问题后回炉范围过大；
6. 结构错误和语义错误混在同一重试链路中；
7. LLM 调用、校验、自动修复、回炉之间缺少可回放的统一 trace。

---

## 2. 设计目标与非目标

### 2.1 目标

1. **结构问题程序化解决**
   - seq、章号、覆盖、线程闭环、forced-skip、synthetic 默认字段由程序负责。
2. **语义问题交给模型或人工**
   - 真相、动机、人物关系、结局选择由模型负责，但必须通过结构合同表达。
3. **小步生成**
   - 每个 LLM 调用只处理一个规划单元，不再让模型一次维护全书。
4. **P6 前预检**
   - 机械问题在 P6 前修复，P6 只审语义。
5. **可断点、可回放**
   - 每个阶段有输入快照、输出快照、校验结果和修复记录。

### 2.2 非目标

1. 不重写整个随机衍生数据库。
2. 不改变主站 `novel_gen`。
3. 不要求一次性删除旧 run 或旧字段。
4. 不把所有语义判断都自动化。
5. 不为了让流程跑通而静默篡改故事真相。

---

## 3. 目标架构

```text
DB 素材池
   ↓
⑤ Sampling / Weld
   ↓
⑥a Story Plan
   ↓
RouteContractCodec
   ↓
PlanNormalizer
   ↓
DeterministicRepair
   ↓
PlanPrecheck
   ↓
⑥b Draft Window
   ↓
DraftNormalizer / Repair
   ↓
P6 Semantic Gate
   ↓
⑦ Write
   ↓
⑧ QC
```

每个箭头之间都有：

- 输入快照；
- 输出快照；
- 错误分类；
- 自动修复记录；
- 续跑点。

---

## 4. 核心模块设计

### 4.1 `RouteContractCodec`

统一处理所有拍合同：

- `BeatAssignment`
- `SyntheticAssignment`
- `SkipAssignment`

统一字段：

```text
chapterNo
beatRole
storyLayer
focusCharacters
threadRefs
dependsOn
payoffTarget
appearanceMode
requiresKnowledge
writesKnowledge
requiresState
changesState
skipReason
```

职责：

- 读取模型 JSON；
- 读取 `beat_instance.route_contract`；
- 写回 `beat_instance.route_contract`；
- 补默认值；
- 做字段级语法校验；
- 输出版本号。

建议接口：

```text
normalize(RouteDraft) -> NormalizedRoute
serialize(RoutePlan) -> JSON
validateSyntax(RoutePlan) -> List<Issue>
```

不再让 `DeriveBeatRouteService`、`DeriveDraftService`、`DerivePlanValidationService` 各自读不同字段。

### 4.2 `FactLedger`

把知识从自由文本升级成事实账。

最小模型：

```text
factId
canonicalText
writers: [characterKey]
readers: [characterKey]
plantedChapter
revealedChapter
status
```

例如：

```json
{
  "factId": "FACT-001",
  "canonicalText": "C2完成详细报告",
  "writers": ["C2"],
  "readers": ["C1"],
  "plantedChapter": 2,
  "status": "OPEN"
}
```

模型引用知识时优先使用 `factId`：

```json
{
  "requiresKnowledge": ["FACT-001"],
  "writesKnowledge": ["FACT-002"]
}
```

程序负责：

- factId 是否有更早的 writer；
- reader 是否真的在该章获得知识；
- 缺 writer 时自动补 discovery beat；
- 无法确定时才报错。

### 4.3 `ThreadLedger`

复用并扩展现有：

```text
derive_story_thread
```

线程状态：

```text
OPEN
ECHOED
CONFIRMED
PAYOFF
CLOSED
```

每个线程必须有：

```text
plantedChapter
echoChapter
payoffChapter
payoffVolume
ownerCharacterKey
```

程序在路由完成时自动检查：

- 是否有埋设拍；
- 是否有回声拍；
- 最终卷是否有兑现拍；
- requiredThreads 是否全部闭环。

如果模型漏写最终兑现拍：

- 优先追加到已有 `FINAL_CLOSURE` 的 `threadRefs`；
- 没有合适拍时自动生成一个 `CLUE_PAYOFF/FINAL_CLOSURE` synthetic beat；
- 修复动作写入审计。

### 4.4 `PlanRepairEngine`

分成两类：

#### A. 自动修复

- forced-skip 被分配；
- skipped/filled 重叠；
- synthetic 缺默认字段；
- 缺线程兑现拍；
- 唯一可定位的知识缺口；
- clue 章号与线程合同唯一对齐；
- 角色状态存在唯一修复路径。

#### B. 不自动修复

- 两套真相方案冲突；
- 凶手/动机/手法互斥；
- 人物生死根本冲突；
- 角色职能无法唯一归位；
- 模型输出本身语义不完整。

自动修复必须保存：

```json
{
  "repairType": "ADD_THREAD_PAYOFF",
  "target": "PLAN_VOLUME:1",
  "before": {},
  "after": {},
  "reason": "required thread GM-CLUE-01 lacked payoff beat"
}
```

### 4.5 `PlanPrecheck`

在 P6 前运行：

```text
syntax
coverage
seq uniqueness
chapter continuity
thread closure
knowledge causality
character life state
required roles
budget
```

输出三级：

```text
ERROR     阻止进入 P6
WARNING   记录但放行
AUTO_FIX  已自动修复并记录
```

P6 只接收已经通过预检的规划。

---

## 5. 错误分类与重试策略

### 5.1 错误类型

```text
PROVIDER_ERROR
  网络、超时、空响应

JSON_ERROR
  截断、语法错误、重复键

SCHEMA_ERROR
  缺字段、类型错误

COVERAGE_ERROR
  seq/章号/线程未覆盖

CAUSALITY_ERROR
  知识、状态、人物先后冲突

SEMANTIC_ERROR
  真相、动机、人物职能冲突
```

### 5.2 重试策略

| 错误 | 处理 |
|---|---|
| PROVIDER_ERROR | 原样重试，最多 2 次 |
| JSON_ERROR | 结构修复后重试，最多 3 次 |
| SCHEMA_ERROR | 喂回字段错误，最多 2 次 |
| COVERAGE_ERROR | 先确定性修复，再重试 1 次 |
| CAUSALITY_ERROR | 先补事实/状态，再重试 1 次 |
| SEMANTIC_ERROR | 进入 P6 或人工处理 |

禁止把：

```text
模型漏线程兑现
```

和：

```text
模型网络超时
```

放进同一个重试计数器。

---

## 6. 提示词重构

### 6.1 小步单元

拆分为：

```text
SCALE
SPINE
VOLUME_PLAN
CHARACTER_PLAN
ROUTE_WINDOW
DRAFT_WINDOW
GATE_REVIEW
```

每次只处理一个窗口或一个规划单元。

### 6.2 输入显式化

例如路由窗口输入：

```json
{
  "windowChapters": [1, 2, 3, 4],
  "allowedSeqs": [7, 8, 23, 24],
  "forcedSkips": [
    {"seq": 1, "reason": "无死亡红线"}
  ],
  "requiredThreads": ["GM-CLUE-01"],
  "factIds": ["FACT-001", "FACT-002"],
  "characterKeys": ["C1", "C2", "C3"]
}
```

提示词明确：

- 只能使用 `allowedSeqs`；
- `forcedSkips` 禁止出现在 assignments；
- requiredThreads 必须兑现；
- synthetic 与 assignments 共用字段；
- 知识必须有 factId 或前置 writer。

### 6.3 输出最小化

模型只输出语义增量：

```json
{
  "assignments": [],
  "syntheticSlots": [],
  "skipped": []
}
```

默认字段、线程闭环、知识补拍由程序补全。

---

## 7. 数据库影响计划

### P0：不迁移

复用现有 JSONB：

- `run.step_status`
- `beat_instance.route_contract`
- `derive_volume_plan.contract`
- `derive_character_plan.contract`
- `derive_story_thread.contract`

新增内容先写入：

- `run.step_status.steps.REPAIR_AUDIT`
- `run.step_status.steps.PRECHECK`

优点：

- 不破坏存量 run；
- 可快速上线；
- 可用 run 39/40 做回放。

### P1：新增事实账

建议新增表：

```text
derive_fact
```

字段：

```text
id
run_id
novel_id
fact_key
canonical_text
writers jsonb
readers jsonb
planted_chapter
revealed_chapter
status
contract jsonb
created_time
updated_time
```

迁移策略：

- 新增表，不删除旧字段；
- 新 run 写 factId；
- 旧 run 只读兼容；
- 必要时再做回填。

### P1：新增修复审计

建议新增表：

```text
derive_repair_log
```

字段：

```text
id
run_id
stage
repair_type
before_json
after_json
reason
target
created_time
```

避免把所有修复历史继续塞进 `run.step_status`。

### P2：新增 Trace

建议新增：

```text
derive_trace_event
```

保存：

```text
stage
node
run_id
input_snapshot_hash
prompt_hash
response_hash
validation_result
repair_result
tokens
latency
created_time
```

完整 prompt/response 仍留在 `llm_call_log`，trace 只负责串联流程。

---

## 8. 代码模块改造顺序

### 8.1 第一批：合同与预检

涉及：

- `PlanContracts`
- `DeriveBeatRouteService`
- `DeriveDraftService`
- `DerivePlanValidationService`
- `StoryPlanningPrompts`

交付：

1. `RouteContractCodec`
2. `PlanPrecheck`
3. synthetic/assignments 共用字段；
4. forced-skip 去重；
5. 知识/线程覆盖检查。

### 8.2 第二批：确定性修复

涉及：

- `DerivePlanService`
- `DeriveBeatRouteService`
- `DerivePlanValidationService`
- `DeriveGateService`

交付：

1. `PlanRepairEngine`
2. 线程兑现自动补拍；
3. 知识缺口自动补 discovery beat；
4. clue 章号唯一对齐；
5. 修复审计。

### 8.3 第三批：流程状态机

涉及：

- `DeriveAutoFlowService`
- `DeriveResetService`
- `DeriveQcService`

交付：

1. `StageRunner`
2. 阶段 checkpoint；
3. 错误分类；
4. 分层重试；
5. 续跑点。

### 8.4 第四批：可观测性

涉及：

- `LlmJson`
- `LlmCallLogDataService`
- `DeriveQueryService`
- 前端 run 详情页

交付：

1. trace 串联；
2. 错误分类展示；
3. 自动修复 before/after；
4. 一键回放当前 run；
5. 失败阶段高亮。

---

## 9. 测试计划

### 9.1 纯函数测试

- `RouteContractCodec`
- `FactLedger`
- `ThreadLedger`
- `PlanRepairEngine`
- `PlanPrecheck`
- `DerivePlanValidationService`

重点覆盖：

- skipped null chapter；
- filled/skipped 重叠；
- forced-skip echo；
- synthetic knowledge fields；
- thread missing payoff；
- knowledge missing writer；
- character life conflict；
- clue chapter mismatch。

### 9.2 回放测试

用真实数据建立 fixture：

```text
run39-null-chapter
run39-skipped-overlap
run39-forced-skip
run39-synthetic-knowledge
run40-gate-round1
run40-gate-round2
run40-knowledge-gap
```

每个 fixture 保存：

- run snapshot；
- relevant route_contract；
- LLM request/response；
- expected issues；
- expected repair actions。

### 9.3 集成测试

- 新 run 从 ⑤ 到 ⑥a；
- P6 前预检；
- ⑥b 回炉；
- ⑦ 正文；
- ⑧ 质检；
- 服务重启后续跑；
- run 清理后级联。

### 9.4 实弹验收

1. 用固定 seed 跑一个短篇；
2. 允许 P6 round1 REWORK；
3. 检查系统是否自动修复线程/知识；
4. 进入⑦写正文；
5. 进入⑧质检；
6. 导出正文并核查章纲；
7. 用 run 39/40 fixture 回放。

---

## 10. 上线阶段

### 阶段 A：只读预检

- 只运行 `PlanPrecheck`；
- 不自动改数据；
- 输出 ERROR/WARNING/AUTO_FIX 建议；
- 对 run 39/40 跑一遍，确认能提前发现问题。

### 阶段 B：小范围自动修复

只开放：

- forced-skip；
- skipped/filled；
- synthetic 默认字段；
- thread payoff；
- knowledge writer。

所有修复写审计，不改变语义真相。

### 阶段 C：接入全流程

`DeriveAutoFlowService` 改为：

```text
build → normalize → repair → precheck → draft → gate → write → qc
```

### 阶段 D：引入 FactLedger

- 新 run 使用 factId；
- 旧 run 兼容；
- P6/⑦ 继续支持旧文本知识；
- 统计 factId 覆盖率。

### 阶段 E：Trace 与回放

- trace 表上线；
- run 详情页显示修复链；
- CI 加入 run 39/40 回放。

---

## 11. 验收指标

### 稳定性

- P6 round1 通过率提升；
- `KNOWLEDGE_NOT_ACQUIRED` 不再因机械缺源停止；
- `REQUIRED_THREAD_OPEN` 可自动补齐；
- `ROUTE_SKIP_DUP` 不再出现；
- `skipped 含非法/重复 seq` 不再出现；
- synthetic 字段丢失问题归零。

### 效率

- 每个 run 的 LLM 调用次数下降；
- SPINE 回炉比例下降；
- 平均 tokens 下降；
- 平均完成时间下降。

### 可运维性

- 每个失败都能看到：
  - 失败阶段；
  - 错误分类；
  - 输入快照；
  - 自动修复；
  - 下一步。
- run 可以从任意 checkpoint 续跑；
- run 39/40 可以被完整回放。

---

## 12. 风险与回滚

### 风险

1. 自动修复可能掩盖真实语义问题；
2. factId 归一化可能误合并相似事实；
3. synthetic 补拍可能改变原定节奏；
4. 旧 run 与新合同不兼容；
5. 修复审计表增长过快。

### 控制措施

1. 自动修复只允许结构字段，不改真相；
2. factId 归一化保留 canonicalText 和来源；
3. synthetic 补拍记录 before/after；
4. 旧 run 使用兼容读取；
5. trace 保留周期和压缩策略。

### 回滚

1. P0 只读预检：关闭开关即可；
2. 自动修复：按 repairType 逐项关闭；
3. FactLedger：回退到旧 `route_contract` 文本读取；
4. StageRunner：回退到现有 `DeriveAutoFlowService`；
5. 数据库新表只增不删，不回滚旧数据。

---

## 13. 推荐实施顺序

### 第一周：P0 结构稳定

1. 统一 RouteContractCodec；
2. PlanPrecheck；
3. PlanRepairEngine 的 forced-skip / synthetic / thread / knowledge；
4. run 39/40 回放测试。

### 第二周：P1 数据账本

1. FactLedger；
2. derive_fact 迁移；
3. derive_repair_log；
4. factId/threadKey 全链路接入。

### 第三周：P2 流程与提示词

1. StageRunner；
2. 小步单元提示词；
3. P6 结构化 issues；
4. 错误分类重试。

### 第四周：P3 可观测性

1. Trace；
2. run 详情诊断页；
3. CI 回放；
4. 实弹短篇全流程。

---

## 14. 最终设计原则

> 模型负责提出故事，不负责保证故事工程正确；程序负责把故事编译成可验证、可执行、可回放的规划。

任何结构问题都应当在：

```text
LLM 输出之后、P6 之前
```

被程序识别、修复或明确拒绝，而不是等到：

```text
P6 → 回炉 → 清空 → 重新规划
```

才暴露。