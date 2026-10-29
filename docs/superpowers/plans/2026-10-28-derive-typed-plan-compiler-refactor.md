# DeriveFlow 强类型输出协议与计划编译器重构 Implementation Plan

> **Superseded:** 本文件保留为历史设计记录，不再是主执行入口。新的主执行计划见 [2026-10-29-derive-refactor-single-pass-plan.md](2026-10-29-derive-refactor-single-pass-plan.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把随机衍生链路从“多个可变阶段 + 模型同时负责结构和内容 + P6 事后发现矛盾”重构为“冻结故事合同 → 确定性编译完整计划 → 机器准入 → 按章逐场生成正文”，使新 run 在 `direct=false` 下一次性通过计划准入，并在无人工干预下写完合同规定的全部章节。

**Architecture:** `StoryContract` 是唯一故事事实源；新增不可变 `PlanSnapshot` 作为唯一计划事实源；模型只输出受 schema 约束的叙事字段，章节号、beat 身份、枚举、引用、状态事件和预算由 Java 生成。完整计划先由确定性校验器审计并准入，准入后冻结；正文阶段按章、按场景局部生成和局部修复，不再触发全书重规划。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16（`derive_gen`）、Jackson、JUnit 5/Mockito、Flyway、Vue 3 读模型。

**Spec:** 本文件。它同时包含系统现状、设计依据、目标契约和实施任务；执行者不需要当前会话上下文。执行前仍必须读取 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md` 和 `docs/architecture/pipeline-contracts.md`。

## Global Constraints

- 不改主站 `novelgen`，只改随机衍生 `novelgen-derive` 链路。
- 用户当轮未明确允许时，禁止 commit/push，禁止部署，禁止修改线上库或重启线上服务。
- 禁止修改历史 Flyway 迁移。当前 derive 最高迁移为 `V138`；本计划从 `V139` 起只新增迁移。
- 管线运行期间禁止 `mvn clean`、禁止重启后端；任何构建前先确认没有活动 run。
- `run.step_status` 不能再作为业务事实的唯一源，只能保留为 UI/旧流程兼容投影；新链路必须读写显式版本化产物。
- 只有 `StoryContract` 可以写故事事实；只有 `StoryPlanCompiler` 可以生成结构字段；只有 `PlanSnapshot` 可以定义章节/拍/线索/线程的稳定身份；只有正文 writer 可以写正文。
- 模型禁止生成或改写以下字段：章节号、beat key、beat role、thread key、clue key、角色 key、知识事实规范 key、跨层引用 ID。
- 所有 LLM JSON 输出必须有节点级 schema、完整枚举白名单、字段路径错误和真实坏输出回放测试。
- 所有 JSONB 写入必须走 mapper XML 显式 `::jsonb`；SQL 只能在 data mapper。
- `PlanSnapshot` 只有 `DRAFT`、`ADMITTED`、`SUPERSEDED` 三态。只有 `ADMITTED` 版本可以进入正文。
- 正文阶段不得修改 `StoryContract` 或已准入的 `PlanSnapshot`；正文问题只能修当前场景/当前章。
- P6 保留兼容端点，但其结构性硬失败由计划准入的确定性审计产生；不得恢复成自由形式 LLM 终审。
- 除非用户明确要求严格质量模式，LLM 语义审阅不得阻止已经通过确定性准入的正文生成。
- “一次过审”指工程准入门：fresh run 的计划 schema、合同、日程、线程、知识、状态、闭包和正文可达性全部一次通过，不承诺主观文笔评分永远 PASS。

## Review Focus

- 模型输出视觉上合法、但不在 `BeatRole` 枚举里的词时，系统必须给出节点、字段路径、非法值和允许值。
- 模型为同一个 `C1` 输出多个状态事实时，wire format 必须允许表达多个事实且不产生重复 JSON 键。
- 两个服务分别从 `step_status`、日程表、beat pool 和 chapter 表读取同一计划时，必须以同一 `plan_revision` 和 hash 为准。
- 计划准入后被正文阶段自动搬拍、重排章节或重写合同时，必须拒绝并报告 revision drift。
- 任何单章生成失败、空正文或门禁失败时，正文循环必须局部修复；不得把整本书退回 `SCALE/SPINE`。

---

## 1. 系统现状

### 1.1 当前运行事实

2026-10-28 评审时：

- 宝塔 `novelgen-derive` 服务 active，监听 8091。
- 线上 jar SHA256：`e48306db0f418f8c3fc61ff5ddccb266a421ecd40e18f9c0108d320f347d7a2e`。
- derive Flyway 为 `138`。
- run58：P6 round 1 `REWORK/PLAN_VOLUME:1`，round 2 `REWORK/DRAFT`，round 3 前强停。
- run59：到 P6 round 1，`REWORK/DRAFT`，5 个问题后强停。
- run60：停在 `PLAN_V2.currentUnit=SLOT_ROUTE`，一次 ROUTE 调用连续三次内容不合规后自然停止。
- 当前工作区未提交，且存在大量既有改动和 CRLF 行尾噪声。执行者不得 reset/checkout/删除。

### 1.2 已证实失败

#### run58

- 合同只有一行 `1 FROZEN`，真凶未漂移。
- HEAD C 连续被拒不是 JSON 语法失败，而是校验要求 `clueKey` 集合等于合同全集，HEAD C 输出没有提供。
- 新业务校验器先落地，prompt/output schema 没有同步。

#### run59

- 日程与合同基本对齐，没有 JSON 拒绝。
- P6 失败集中在：
  - reveal 章没有动机兑现；
  - 被控者解回动作没有章纲证据；
  - seq22 被 ⑥b 负载均衡从第 14 章搬到第 1 章；
  - reveal 章只写 culprit+method；
  - 第 14 章只有一笔带过。
- 根因是结构字段在后续阶段仍可变，以及 P6 承担了计划编译期应发现的约束。

#### run60

- 合同一行 FROZEN；日程 12 行；reveal 章有 payoff。
- id 3141：`BeatRole=OBSTACLE`，同时有 `DISCOVERY`；
- id 3142：`C1` 在状态对象中重复出现；
- id 3143：`BeatRole=MIDPOINT`，同时有 `ESCALATION`。
- 三次都 `finish_reason=stop`，输出 9–15K 字符，不是截断。
- `beat_instance=0`、`derive_gate_review=0`，计划一致性和 P6 未验证。

#### 58 前也不是“没有 JSON 问题”

线上 `llm_call_log` 已验证：

- run44–57 存在大量格式/契约拒绝。
- run44 仅被拒绝输出约 50 次。
- run44 多次出现与 run60 相同的重复 `C1`。
- run44 id 2149 失败后，2150 重试成功，所以 run 级 `lastError` 为空。
- UI 把 HTTP `status=ok` 显示为“成功”，即使旁边 `error_msg` 写着重复键/非法枚举。
- “之前没怎么报”实际是重试成功掩盖了中间失败。

### 1.3 当前代码结构问题

#### 事实源过多

同一计划事实散落在：

- `derive_story_contract`
- `run.step_status.TRUTH_CONTRACT/STORY_CONTRACT/STORY_SPINE`
- `derive_volume_plan`
- `derive_character_plan`
- `derive_story_thread`
- `derive_chapter_schedule`
- `derive_beat_pool`
- `beat_instance`
- `derive_chapter_contract`
- `chapters.plan_contract`
- `chapters.draft_outline`

不同阶段各读一部分、改一部分，任何同步遗漏都会变成 P6 的“发现型失败”。

#### 模型承担了机械工作

当前 ROUTE/⑥b 让模型同时决定：

- 章节/拍对应关系；
- `beatRole`；
- `state` 的 JSON 形状；
- 线索与线程引用；
- 故事内容；
- 部分回炉结构。

模型不是编译器。它适合写场景目标、冲突、动作和台词，不适合生成稳定 ID、枚举和唯一键。

#### 校验发生在错误时间

P6 在章纲完成后才检查合同、日程、章号和闭合。正确时机是：

1. 合同后；
2. 计划编译后、写正文前；
3. 正文之后只做文本质量与事实一致性检查。

#### 核心服务过大

当前关键类规模：

- `DeriveDraftService.java` 约 2227 行；
- `DeriveBeatRouteService.java` 约 2148 行；
- `DerivePlanService.java` 约 1498 行；
- `DeriveStoryPlanService.java` 约 1446 行；
- `DeriveGateService.java` 约 973 行；
- `DeriveWriteService.java` 约 852 行。

这些类内部混合 LLM 调用、JSON 容错、业务校验、数据库写、回炉和兼容逻辑。继续加别名只会扩大耦合。

#### 正文生成仍有潜在 JSON 风险

`DeriveWriteService` 目前要求模型在正文末尾追加机器可读的「拍兑现契约」JSON。最长文本节点反而又承担结构化尾巴。新方案必须把散文生成与结构校验分开。

### 1.4 为什么不能继续打补丁

继续“出现新枚举就加别名、出现新坏 JSON 就加 repairer”会导致：

- 每次上下文变化都可能出现新词；
- 重试反馈缺少字段路径；
- 同类错误换字段复发；
- 测试只能证明已知样例；
- P6 兜底本可提前验证的问题；
- 局部修复破坏另一阶段的数据一致性。

所以必须重构生成协议和计划编译边界。

---

## 2. 目标与边界

### 2.1 工程可保证的目标

- 新 run 的合同恰好一份有效版本。
- 所有 LLM JSON 符合节点 schema。
- 所有结构 ID、章号、枚举、引用由 Java 生成。
- 计划快照完整、不可变、版本可追踪。
- 所有线索、线程、知识、状态、reveal、closure 在写正文前通过确定性校验。
- 进入正文后，计划不得被自动改写。
- 每章至少生成一份非空正文。
- 单章失败只触发当前章的场景级修复。
- 全流程可断点续跑，失败原因持久化。
- 计划准入首轮通过后，从第一章跑到最后一章，中途不因 P6 重跑全书。

### 2.2 不承诺的内容

不能承诺任意随机题材下的主观文笔、节奏和情绪力度永远让 LLM 评审第一轮满意。质量分为：

- 结构质量：确定性校验，硬失败；
- 语义质量：结构化评分，可用于候选选择或告警；
- 文笔建议：不阻塞正文完成。

若业务坚持“LLM 评审必须第一轮 PASS 才能继续”，不存在可证明的单次成功率，只能通过多候选计划提高概率。本计划默认采用“确定性计划准入 + 语义评分不阻断正文完成”。

---

## 3. 目标架构

```text
RunSpec
  → StoryContract
  → StoryPlanCompiler
      → deterministic schedule
      → deterministic beat assignment
      → typed narrative expansion
      → continuity simulation
  → PlanSnapshot(revision=N)
  → PlanAuditor
  → PlanAdmission(ADMITTED, revision=N)
  → ChapterScripts
  → per-scene prose generation
  → ChapterAssembler
  → DraftBundle
  → FinalAudit
```

### 3.1 单一事实源

| 产物 | 唯一写入者 | 主要读取者 |
|---|---|---|
| `StoryContract` | `StoryContractService` | 所有规划、正文、审计 |
| `PlanSnapshot` | `StoryPlanCompiler` | 章纲、正文、P6、审计、UI |
| `ChapterDraft` | `ChapterWriter` | 后续章、定稿、审计 |
| `AuditReport` | `PlanAuditor` / `ChapterAuditor` | UI、回炉、发布门 |

`run.step_status` 只保留兼容投影，不再让新业务读取“最新合同”或“当前拍分配”。

### 3.2 版本规则

- `StoryContract.version` 冻结后不增长。
- `PlanSnapshot.revision` 每次重新编译递增。
- `PlanSnapshot.contractVersion` 与 `contractHash` 必须匹配。
- `run.active_plan_revision` 指向唯一已准入版本。
- 下游必须携带 `expectedRevision`。
- revision 不一致时抛 `PLAN_REVISION_STALE`，不得自动读最新继续。

### 3.3 分层职责

- Contract 层：只定义故事事实。
- Plan 层：只定义卷、章、拍、场景、状态事件、线索与揭示。
- Prose 层：只把 `ChapterScript` 写成散文，不生成结构数据。
- Audit 层：只读输入并产生结构化报告，不修改输入。

---

## 4. 目标数据模型

### 4.1 V139：计划快照

新增 `novelgen-data/src/main/resources/db/migration-derive/V139__derive_plan_snapshot.sql`：

```sql
ALTER TABLE run ADD COLUMN active_plan_revision INTEGER;
ALTER TABLE chapters ADD COLUMN plan_revision INTEGER;
ALTER TABLE beat_instance ADD COLUMN plan_revision INTEGER;

CREATE TABLE derive_plan_snapshot (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    contract_version INTEGER NOT NULL,
    contract_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    plan_hash VARCHAR(128) NOT NULL,
    graph JSONB NOT NULL,
    admission_report JSONB,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_plan_snapshot_run_revision UNIQUE (run_id, revision),
    CONSTRAINT ck_derive_plan_snapshot_status
        CHECK (status IN ('DRAFT', 'ADMITTED', 'SUPERSEDED'))
);

CREATE INDEX idx_derive_plan_snapshot_run_revision
    ON derive_plan_snapshot(run_id, revision DESC);
```

`chapters.plan_revision` 与 `beat_instance.plan_revision` 用于拒绝正文/场景混用旧计划。

### 4.2 V140：持久阶段执行账

新增 `novelgen-data/src/main/resources/db/migration-derive/V140__derive_stage_run.sql`：

```sql
CREATE TABLE derive_stage_run (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    stage VARCHAR(48) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    input_plan_revision INTEGER,
    output_plan_revision INTEGER,
    error_code VARCHAR(64),
    error_path TEXT,
    error_message TEXT,
    lease_owner VARCHAR(128),
    lease_expires_time TIMESTAMPTZ,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_stage_run_attempt UNIQUE (run_id, stage, attempt),
    CONSTRAINT ck_derive_stage_run_state
        CHECK (state IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_derive_stage_run_active
    ON derive_stage_run(run_id, stage, state);
```

### 4.3 PlanSnapshot graph

`graph` 是完整聚合，不允许下游再从多表拼同一计划：

```json
{
  "scope": {},
  "volumes": [],
  "characters": [],
  "chapters": [
    {
      "chapterNo": 1,
      "volumeNo": 1,
      "title": "…",
      "budget": 1800,
      "script": {
        "goal": "…",
        "scenes": [
          {
            "sceneNo": 1,
            "beatKeys": ["B01"],
            "goal": "…",
            "entryState": [],
            "exitState": [],
            "mustCarryKnowledge": [],
            "mustRevealKnowledge": []
          }
        ]
      }
    }
  ],
  "beats": [
    {
      "beatKey": "B01",
      "chapterNo": 1,
      "sceneNo": 1,
      "beatRole": "HOOK",
      "goal": "…",
      "focusCharacters": ["C1"],
      "threadRefs": [],
      "knowledgeEffects": [],
      "stateEffects": []
    }
  ],
  "clues": [],
  "threads": [],
  "stateTimeline": []
}
```

结构字段只能由 Java 写；叙事字段可由 LLM 填。

---

## 5. 目标 JSON 协议

### 5.1 不再让模型输出 `BeatRole`

`BeatRole`、章节号、beat key 由 `BeatAssignmentSolver` 生成。模型只输出叙事字段：

```json
{
  "beatNarratives": [
    {
      "beatKey": "B01",
      "goal": "调查者发现门缝里有被压扁的纸屑",
      "knowledgeEffects": [
        {"characterKeys": ["C1"], "fact": "门缝里有被压扁的纸屑"}
      ],
      "stateEffects": [],
      "threadEffects": []
    }
  ],
  "skipped": [
    {"beatKey": "B07", "reason": "与无第二案件红线冲突"}
  ]
}
```

### 5.2 状态字段使用“键一次 + 值数组”

禁止：

```json
{"C1":"已接案","C1":"已发现纸屑"}
```

允许：

```json
{"C1":["已接案","已发现纸屑"]}
```

Java 类型：

```java
Map<String, List<String>> requiredStateBefore
Map<String, List<String>> requiredStateAfter
```

### 5.3 枚举来源唯一

`BeatRole`、`StoryLayer`、`AppearanceMode`、`CharacterFunction`、`ThreadKind` 的 prompt 文本、schema enum、解析器校验和测试列表必须来自同一 Java 定义。新节点输出不得依赖模糊 alias。

---

## 6. 目标文件结构

### LLM 协议模块

`novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/contract/`

- `ContractViolation.java`
- `NodeOutputContract.java`
- `NodeOutputContractRegistry.java`
- `JsonSchemaSupport.java`

### 计划编译模块

`novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/`

- `PlanCompileInput.java`
- `CompiledStoryPlan.java`
- `PlanCompileResult.java`
- `StoryPlanCompiler.java`
- `BeatAssignmentSolver.java`
- `ContinuitySimulator.java`
- `PlanCompilerException.java`

### 计划审计模块

`novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/`

- `PlanAuditIssue.java`
- `PlanAuditReport.java`
- `PlanAuditor.java`
- `PlanAdmissionService.java`
- `SemanticPlanReviewService.java`

### 正文模块

`novelgen-service/src/main/java/com/zzdzz/novelgen/derive/draft/`

- `SceneScript.java`
- `ChapterScript.java`
- `SceneDraft.java`
- `ChapterDraftResult.java`
- `SceneWriter.java`
- `ChapterAssembler.java`
- `ChapterAuditor.java`

### 编排模块

`novelgen-service/src/main/java/com/zzdzz/novelgen/derive/orchestration/`

- `DeriveStage.java`
- `StageRunState.java`
- `DeriveRunOrchestrator.java`

---

## 7. 执行计划拆分
这份文件是总设计、系统现状和不可变决策。实施拆成三份子计划，必须按顺序执行：

1. [输出协议与可观测性](2026-10-28-derive-output-contract-hardening.md)
2. [计划编译与准入](2026-10-28-derive-plan-compiler-admission.md)
3. [正文闭环与持久编排](2026-10-28-derive-durable-draft-completion.md)
4. [独立执行 AI 提示词](2026-10-28-derive-refactor-executor-prompt.md)

拆分原因：

- 输出协议是计划编译的前置依赖；
- 计划编译与准入只处理结构，不碰正文；
- 正文闭环依赖已准入的计划，但不能反向修改计划；
- 三份子计划都能各自交付可测试软件，避免一个任务同时改 schema、编译器、P6、正文和编排。

执行顺序固定。每份子计划完成后都要跑该计划的完成检查，再进入下一份。

## 8. 全局验收目标

最终只接受以下事实作为完成：

- 三份子计划的 DoD 全部满足；
- 新 run 使用 `compiler-v1`，不是 legacy；
- `StoryContract` 恰好一份 FROZEN；
- `PlanSnapshot` 恰好一个 ADMITTED；
- 计划 schema 和质量硬审计首轮通过；
- 正文阶段 plan revision 不变；
- 所有规定章节都有非空正文；
- run 到达 `DRAFT_COMPLETE`；
- 未获得用户当轮授权时没有 commit/push/deploy。
