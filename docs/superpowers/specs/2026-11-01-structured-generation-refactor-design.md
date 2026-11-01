# 结构化生成重构设计

日期：2026-11-01  
状态：设计稿，待评审  
范围：随机衍生系统 `novelgen-derive-app` / `novelgen-service`

## 1. 目的

当前业务目标已经是：

```text
类型合同
→ 统一故事内核
→ 结构化章节计划
→ 结构化场景计划
→ 正文
→ 事件落账
```

但现有实现仍存在多处“自由生成 + 事后审计”的泄漏点，导致：

- 章纲与合同不一致；
- 章纲与正文不一致；
- 人物、关系线在合同中存在，但下游计划缺失；
- 世界状态由模型重复总结，产生冲突；
- 审计发现问题后只能打回，无法证明生成过程本身遵守了结构。

本设计把结构从提示词文本提升为编译器类型和不变量，让 LLM 只填充叶子字段。

## 2. 成功标准

重构完成后必须满足：

1. 所有角色、关系线、事件、义务都从统一注册表产生；
2. 章节计划是结构化对象，自由文本只能是展示字段；
3. 正文由场景计划展开，不能独立于场景计划自由生成；
4. 世界状态由事件增量确定性投影，模型不能覆盖整份快照；
5. 结构不合法的产物无法落库；
6. 审计只判断语义、文风和读者可感知质量，不承担结构修复；
7. 新增类型只需要类型合同和类型插件，不需要新增域格或状态机分支；
8. `draft` 模式可以先交付初稿，但结构不变量仍然必须成立；
9. `strict` 模式在结构合法的基础上再要求语义审计通过。

## 3. 非目标

- 不在本设计中引入新的文笔模型；
- 不在本设计中扩大到 100+ 章长篇性能优化；
- 不把剧情迁移实现成原文复制；
- 不继续用更多审计规则补偿结构缺陷；
- 不改变现有对外 run API 的基本路径。

## 4. 核心原则

### 4.1 合同是唯一源

`GenreContract` 是角色、关系、义务和阶段的唯一源。下游只能引用合同投影，不能重新发明合同内容。

### 4.2 ID 先于文本

角色、关系线、事件和场景先获得稳定 ID，再生成名称和文本。

禁止下游出现未注册引用，例如：

```text
C1-C2
关系从陌生到确认
未知角色 C1
未知线程 REL-C1-C2
```

### 4.3 生成与校验同缝

结构校验发生在场景计划提交前，不是正文写完后才审计。

### 4.4 审计不修结构

审计可以报告问题，但不能补角色、补关系线、改章号、重写世界状态或自动改合同。

## 5. 统一数据模型

### 5.1 `StoryKernel`

由类型合同编译得到，包含：

```text
entities          角色实体
relationships     关系线
events            规划事件
obligations       义务
schedule          章级日程
genreStages       类型阶段
```

每个元素都带稳定 ID 和来源合同版本。

### 5.2 `ChapterPlan`

每章一份，至少包含：

```json
{
  "chapterNo": 4,
  "volumeNo": 1,
  "stage": "CONFLICT_PEAK",
  "actors": ["C1", "C2"],
  "target": "C2",
  "requiredEventIds": ["E4-CONFLICT"],
  "obligationKeys": ["CH4:CHARACTER_COST:C2"],
  "stateBefore": ["REL-C1-C2:TENSION"],
  "stateAfter": ["REL-C1-C2:LOW"]
}
```

`summary`、`title` 等自然语言字段只用于展示，不作为下游事实源。

### 5.3 `ScenePlan`

场景是正文生成的最小结构单元，至少包含：

```json
{
  "sceneId": "SC4-01",
  "chapterNo": 4,
  "eventIds": ["E4-CONFLICT"],
  "actorKeys": ["C1", "C2"],
  "targetKey": "C2",
  "dialoguePlan": [],
  "stateDeltas": [
    {
      "key": "REL-C1-C2",
      "from": "TENSION",
      "to": "LOW"
    }
  ],
  "proof": "两人发生当面争执"
}
```

LLM 只允许填充台词、动作、细节和状态值，不允许修改结构字段。

### 5.4 `StateDelta`

世界状态变更必须表达为增量：

```json
{
  "key": "REL-C1-C2",
  "from": "WARMING",
  "to": "CONFLICT",
  "sourceEventId": "E4-CONFLICT"
}
```

系统负责校验、合并和版本递增。

### 5.5 `ProseBlock`

正文按场景块生成：

```text
SC4-01 → 第4章第一场
SC4-02 → 第4章第二场
```

`chapters.content` 是场景块的拼装结果，不再是一段与计划脱钩的自由文本。

## 6. 目标模块与接口

### 6.1 `StoryKernelCompiler`

```java
StoryKernel compile(
    GenreContract contract,
    ScopeContract scope,
    SampleAssets assets
);
```

职责：

- 注册角色；
- 注册关系线；
- 投影类型义务；
- 生成章级日程；
- 输出统一 `StoryKernel`。

该模块内部可以组合多个小组件，但外部只有一个编译接口。

### 6.2 `ChapterPlanCompiler`

```java
List<ChapterPlan> compile(
    StoryKernel kernel,
    VolumePlan volumePlan
);
```

职责：

- 把合同阶段和日程编译成章节计划；
- 保证章号连续；
- 保证每章引用的事件、义务和状态键都存在；
- 生成展示用摘要，但不把摘要当事实源。

### 6.3 `ScenePlanGenerator`

```java
ScenePlan generate(
    ChapterPlan chapterPlan,
    StateVersion state,
    CastRegistry cast
);
```

职责：

- 调用 LLM 填充场景叶子字段；
- 返回结构化 `ScenePlan`；
- 对返回结果执行构造校验；
- 只重生成失败场景，不重置整本书。

### 6.4 `ProseComposer`

```java
ProseBlock compose(
    ScenePlan scenePlan,
    StateVersion state,
    StyleProfile style
);
```

职责：

- 根据场景计划写正文；
- 保留场景 ID 到正文块的映射；
- 不允许正文反向修改场景计划。

### 6.5 `StateLedger`

```java
StateVersion commit(List<StateDelta> deltas);
StateVersion current();
```

职责：

- 校验状态键；
- 校验 `from` 是否等于当前值；
- 应用增量；
- 生成版本化快照；
- 检测冲突。

`world_states` 改为该账本的读投影，不再由模型直接写整份状态。

### 6.6 `StructuralValidator`

```java
ValidationResult validate(StoryKernel kernel);
ValidationResult validate(ChapterPlan plan);
ValidationResult validate(ScenePlan plan, StateVersion state);
```

只有结构合法的产物才能提交。失败时返回可定位到字段的错误。

### 6.7 `SemanticAuditor`

```java
AuditReport audit(ProseBlock block, ChapterPlan plan);
```

只负责文风、节奏、情感、幻觉和读者可感知逻辑问题，不负责修结构。

## 7. 数据流

```text
GenreContract
    ↓
StoryKernelCompiler
    ↓
StoryKernel
    ↓
ChapterPlanCompiler
    ↓
ChapterPlan[]
    ↓
ScenePlanGenerator ──→ StructuralValidator
    ↓
ScenePlan
    ↓
ProseComposer
    ↓
ProseBlock
    ↓
StateLedger.commit
    ↓
StateVersion
    ↓
下一场景
```

任何一层失败都只影响当前编译单元。

## 8. 与现有代码的映射

| 现有模块 | 目标职责 |
|---|---|
| `GenreContract`、`RomanceContract`、`MysteryContract` | 保留，作为类型合同源 |
| `DeriveStoryPlanService` | 拆出 `StoryKernelCompiler` 与 `ChapterPlanCompiler` |
| `DeriveBeatRouteService` | 只做结构化拍装配，不再发明角色或线程 |
| `DeriveDraftService` | 拆成 `ScenePlanGenerator` 与 `ProseComposer` |
| `world_states` | 改为 `StateLedger` 的投影 |
| `DeriveGateService` | 保留语义审计，不再承担结构修复 |
| `DeriveQcService` | 保留内容质检，结构问题由构造校验拦截 |
| `DeriveSampleService` | 继续负责素材采样与域格解析，不参与故事结构生成 |

## 9. 错误处理

### 9.1 结构错误

例如未知角色、章号越界、状态 `from` 不匹配、义务没有事件。

处理方式：

- 返回字段级错误；
- 只重生成当前场景或当前计划单元；
- 不清整本书；
- 不把 run 直接打成终态失败。

### 9.2 语义错误

例如人物语气不一致、情感转折突兀、文风偏离。

处理方式：

- `draft` 模式记录为报告；
- `strict` 模式按严重度回炉；
- 不修改合同和结构计划。

### 9.3 模型输出错误

JSON 或 schema 错误只重试当前 LLM 调用，不能导致全书重采样、合同重新生成或已完成场景被清空。

## 10. 持久化建议

保留现有表，新增或调整以下逻辑存储：

```text
derive_genre_contract       类型合同
derive_story_kernel         统一故事内核快照
chapters.plan_contract      ChapterPlan JSONB
derive_scene_plan           ScenePlan
derive_state_event          StateDelta 事件
world_states                StateVersion 读投影
chapters.content            ProseBlock 拼装结果
```

每个持久化对象都记录：

```text
contractVersion
kernelVersion
planHash
sourceEventIds
```

用于追溯“哪份合同生成了哪章正文”。

## 11. 迁移阶段

### 阶段 0：模型和不变量

- 定义 `StoryKernel`、`ChapterPlan`、`ScenePlan`、`StateDelta`；
- 写结构不变量测试；
- 不切换生产链。

### 阶段 1：双写投影

- 现有链继续生成；
- 同时把现有章纲、正文、世界状态投影到新模型；
- 用差异报告找出旧链泄漏点。

### 阶段 2：先切言情

- 言情完整走新链；
- 先验证关系阶段、人物注册和事件落账；
- 保留旧链回退开关。

### 阶段 3：切悬疑

- 接入线索、真相、揭示和证据；
- 验证闭合义务；
- 对比旧链正文和新链正文。

### 阶段 4：切换质检语义

- `draft` 模式只要求结构合法；
- `strict` 模式要求结构合法 + 语义审计通过；
- `PASSED` 状态拆分为明确的交付等级。

## 12. 测试策略

### 单元测试

- 合同到 `StoryKernel` 的 ID 完整性；
- `ChapterPlan` 章号和义务覆盖；
- `ScenePlan` 引用合法性；
- `StateDelta` 合并和冲突；
- 正文块与场景 ID 映射。

### 性质测试

- 任意合法合同都只能生成已注册 ID；
- 任意场景提交后状态版本严格递增；
- 任意正文块都能反查到唯一场景计划。

### 黄金样本测试

- 固定言情合同；
- 固定悬疑合同；
- 比较结构输出，不比较文风随机性。

### 真实探针

- 言情短篇；
- 悬疑短篇；
- 每个探针检查合同、章节计划、场景计划、状态账和正文。

## 13. 验收标准

重构完成后，真实 run 必须满足：

1. 没有未知角色、未知关系线、未知事件；
2. 每章 `ChapterPlan` 与正文场景一一对应；
3. 合同阶段在对应章节有结构化事件；
4. 世界状态没有不可解释冲突；
5. `draft` 模式的问题全部可追溯到具体计划或场景；
6. `strict` 模式只有在结构和语义都通过后才允许 `PASSED`；
7. 新增类型不需要新增域格 Java 分支；
8. 全量测试和真实探针通过。

## 14. 风险与控制

| 风险 | 控制 |
|---|---|
| 新模型与旧链长期双轨 | 每阶段设切换点和回退开关 |
| LLM 仍试图改结构 | schema 校验 + 字段白名单 + 局部重试 |
| 事件账本过于严格 | 允许状态值扩展，但不允许未注册键 |
| 正文变成模板腔 | `ProseComposer` 只约束结构，不约束句式 |
| 迁移期间旧 run 读取失败 | 保留旧读路径和兼容投影 |

## 15. 最终判断

本重构不是“再加一层审计”，而是把系统从：

```text
提示词约束 → 自由生成 → 事后审计
```

改成：

```text
合同编译 → 结构化生成 → 事件落账 → 语义审计
```

审计仍然存在，但它从“纠错器”降回“验收器”。
