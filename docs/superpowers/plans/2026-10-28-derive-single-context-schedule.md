# DeriveFlow 全链路单一上下文与章级日程（一次过 P6）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans。按任务顺序执行，每个 Task 先写失败测试再实现；不要跳步，不要在未获授权时 commit/push/部署。

**Goal:** 让 ⑤采样 → ⑥a（SCALE/CONTRACT/SCHEDULE/SPINE/VOLUMES/CHARACTERS/SLOT_ROUTE）→ ⑥b（HEAD/BATCH）→ P6 → ⑦ 始终只引用**同一份冻结合同 + 它的机械投影（章级日程）**。任何节点提出新的 culprit/motive/method、cast 姓名、线索章号或线索体系，都在**该节点内**被确定性校验拒绝，P6 只评质量，新 run 第 1 轮 P6 即通过。

**Architecture:** 合同（`derive_story_contract`，唯一写者）→ `ChapterScheduleProjector`（纯函数，**不是 LLM 节点**）→ `derive_chapter_schedule`（每章 mustPlant/mustPayoff/reveal，只读投影）→ SPINE 线程 / VOLUMES 卷合同 / SLOT_ROUTE 章功能槽 / ⑥b BATCH / P6 全部只读它。路由与章纲只允许在“日程给定的章号”上写内容；Java 负责机械注入必有的埋设/兑现拍，并做强校验。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16（`derive_gen`）、Jackson、JUnit 5/Mockito、Flyway。

**Spec:** 本文件；实施前同时读 `docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`、`docs/superpowers/plans/2026-10-27-derive-contract-first-single-context.md`（上一批已落地，本批在其上继续）。

---

## Global Constraints（违反即返工，不要“优化”掉）

- 不改主站 `novelgen`，只改随机衍生 `novelgen-derive` 链路；不改历史迁移，新增迁移只能是 `V138`。
- **只有 CONTRACT 单元可以写故事事实**（cast/truth/clues/threads/forbidden）。SCHEDULE 是它的纯函数投影；SPINE/VOLUMES/CHARACTERS/ROUTE/BATCH/P6 一律只读。
- **不新增 LLM 节点**。本批所有新逻辑都是 Java 确定性代码；提示词只做约束收紧。
- **不允许再让 LLM 决定“哪一章埋/兑哪条线索”**：章号只能来自合同，Java 机械注入。
- 所有 JSONB 写入必须 XML 显式 `#{x}::jsonb`；SQL 只准出现在 mapper XML。
- 所有 LLM 结构化输出必须经 `LlmJson` 解析 + Java 二次校验；失败喂回（3 轮）后 **fail-fast**，禁止静默降级。
- 合同一旦 `FROZEN`，P6 不得改写；P6 的 `directive` 只能描述文字重写，不得要求移动线索章号/改真凶手法。
- 回炉最小化：`DRAFT` 只重写问题涉及的章/窗；`PLAN_VOLUME:n` 只重排该卷；只有 `SCALE` 才允许全书重建。
- **未经用户当轮明确允许，不 commit/push、不部署**；部署前必须记录旧 jar SHA256 并备份。
- 管线运行中禁止 `mvn clean`、禁止重启后端（AGENTS #5/#6）。先杀进程再起，`netstat ... | grep LISTENING` 正确姿势见 AGENTS #15。

---

## 0. 执行前必读与现状保护（无代码改动）

- [ ] Read `最高约束.md`（宪法闸，必须先读；否则 Edit/Write 会被引擎取消）。
- [ ] Read `AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md` 末两节、`docs/architecture/pipeline-contracts.md §五/§十`。
- [ ] `git status --short`。**工作区已有大量未提交改动**（上一批 contract-first + 角色业务模型），禁止 `git reset --hard` / `git checkout --` / 删除未跟踪文件。
- [ ] 记录基线：`git log -1 --format="%h %ad %s" --date=iso`；`mvn -q test` 当前应全绿（上一批记录 613 tests）。
- [ ] 确认本地/线上 `derive_gen` schema ≥ 137；本批新增 V138（只加表/列，不改历史）。
- [ ] 找一个空闲 run 做实验；`run58` 是只读证据，**不要恢复它**（第 3 轮 P6 的合同与章纲仍是旧逻辑产物）。

**验收:** 能复述三句话：合同唯一写者；日程是纯函数投影；任何偏离在产出节点内拦截，不到 P6。

---

## 1. 问题定义与“一次过”的验收口径

### 1.1 什么叫“全流程上下文一致”

同一次 run 内，下列对象必须是**同一版本**、可互相推导，不允许出现第二套：

| 对象 | 存放 | 谁写 | 谁读 |
|---|---|---|---|
| 故事合同 | `derive_story_contract` | CONTRACT 单元 | 所有下游、P6、⑦/⑧ |
| 章级日程 | `derive_chapter_schedule`（V138） | SCHEDULE 单元（纯函数） | ROUTE / ⑥b / P6 |
| 线程账 | `derive_story_thread`（带章号） | SPINE 投影 | ROUTE / P6 |
| 卷合同 | `derive_volume_plan` | VOLUMES（LLM 写叙述，Java 填合同字段） | CHARACTERS / ROUTE / P6 |
| 角色计划 | `derive_character_plan` | CHARACTERS（合同 cast 约束） | ROUTE / P6 |
| 章功能槽 | `derive_chapter_contract` + `chapters.plan_contract` | SLOT_ROUTE（由日程派生） | ⑥b / P6 |
| 拍 | `beat_instance` / `derive_beat_pool` | SLOT_ROUTE | ⑥b / ⑦ |
| 章纲 | `chapters.draft_outline` | ⑥b BATCH | P6 / ⑦ |

### 1.2 “一次过”的定义（本批验收）

新 run（run59）在 `direct=false` 下：

- `derive_story_contract` 恰好 1 行 `FROZEN`，`version=1`，到 P6 结束不增长；
- `/auto-progress` 阶段顺序：`SCALE → CONTRACT → SCHEDULE → SPINE → VOLUMES → CHARACTERS → SLOT_ROUTE → ⑥b → P6`；
- **P6 第 1 轮 `GO/PASS`**，`derive_gate_review` 只有 1 行；
- `derive_chapter_schedule` 与合同逐条对齐（合同声明的每条 clue 的 plant/payoff、reveal 章；条数以当次合同为准，不写死 6）；
- 所有拍 `threadRefs` 只出现在日程允许的章；
- 章纲 `clueEvents` 与日程完全一致，无重复埋点、无合同外机关；
- 没有 `CLUE_PLANT_MISMATCH`、`CONTRACT_INVALID`、`HEAD_C_TRUTH_CONFLICT`；
- 允许 `ADVISORY` 级 note（文笔建议），但不得有 `FIXABLE/HARD`。

> 若第 1 轮仍 `REWORK`：**立即 kill，不再自动回炉**；按 §10.4 分类（结构类=实现 bug，内容类=提示词/模型问题），把首个分歧点写清再决定下一批，禁止为了过闸放宽 P6。

---

## 2. 现状与根因（executor 不需要重新推导，但必须复核关键行）

### 2.1 run58 实测证据（2026-10-27/28，已停在第 3 轮 P6 途中）

- 合同：1 行 `1 FROZEN`，cast=3（沈砚/程守义/陆文柏），culprit=陆文柏，reveal=11，clues=6。
- 阶段：00:22:58 SCALE → 00:24:31 CONTRACT → 00:24:38 SPINE → 00:26:58 ⑥b → 00:31:09 P6 r1。
- P6 r1 `REWORK/PLAN_VOLUME:1`（11 项），P6 r2 `REWORK/DRAFT`（8 项）；00:45:16 起 r3 调用，00:46:12 被 `POST /auto/kill` 强停（`llm_call_log` id=3115 中断）。
- 章纲与合同错位：CLUE-04 收据应埋 4 章，章纲 2 章已写；CLUE-03 压痕应埋 6 章，章纲 5 章已写；CLUE-06 发带应埋 9 章，章纲 8 章已写且 9 章重复；CLUE-02 应兑 11 章，章纲 12 章又写一次；ch6 出现合同外“铁板封窗”机关。
- 合同自身二义：`method` 写“使程守义**抄录**伪造日记”，`CLUE-02` 写“字迹与程守义笔迹不同”，`finalTruth` 写“伪造日记均出自陆文柏之手”——三种说法互斥，P6 两轮都报。

### 2.2 代码断点（按数据流顺序，行号以当前工作区为准）

| 断点 | 代码位置 | 后果 |
|---|---|---|
| 合同 clue 章号投影丢失 | `DeriveStoryPlanService.spineFromContract` L344-382；`persistSpine` L628-651 | `ThreadContract` 只有卷号，`derive_story_thread.contract={}`，表无章号列 |
| 卷合同只有线程 key | `PlanContracts.VolumeContract`（L138-154） | `requiredThreads` 无章号 |
| 章功能槽是位置模板 | `DeriveBeatRouteService.chapterSlots` L1362-1408 | ch3 固定 `CLUE_PLANT`，但不知道埋哪条 |
| 路由输入无合同 | `DeriveBeatRouteService.buildRouteUser` L1289-1334 | 只有 volumeContract/characters/threads/beats，没有 FROZEN_CONTRACT |
| 机械装箱搬拍 | `DeriveChapterPacker.pack` L20-90（`protectedRole` L111-115） | CLUE_PLANT/CLUE_PAYOFF 不在保护集，会被搬到空章 |
| 负载归一搬拍 | `DeriveBeatRouteService.rebalanceRouteLoad` | 同样不保护线索拍 |
| BATCH 只查人名/编号 | `DeriveDraftService.validateBatch` L642-688 | 不查 clue 章号、不查重复、不查合同外机关 |
| HEAD C 必失败 | `HEAD_C_PROMPT` L126-150 不要求 clueKey，`validateHeadC` L590-634 要求 clueKey 集合相等 | 每轮 3 次失败后降级（run58 logId 3107-3110） |
| P6 预检不查日程 | `StoryContractValidator.validateAgainstPlan` L319-366；`DeriveGateService.contractIssues` L322-352 | 两轮都只能 REWORK，不走 `CONTRACT_INVALID` |
| P6 预检不阻塞（bug） | `DeriveGateService.gate` L219-229 + `decideGate` L570-595 | `preflight` 只进 issues、不产生 targets，criteria 为空时仍判 `GO/PASS` |
| 回炉整本清稿 | `DeriveDraftService.draftInner` L282-296（status=REWORK 时 `clearForRewrite`/`clearDraftOutlines`/`clearDraftAssets` 全清） | DRAFT 目标 = 全书重刷，ch8 发带就是重刷时写进去的 |
| P6 指令可改合同 | run58 r2 `directive` 写“留在第 8~9 章”（合同 9 章） | 回炉自己制造下一轮问题 |

### 2.3 根因链（一句话版）

合同有线索章号，但**没有任何机制把它投影到章级计划**；route 拿不到、packer 会搬走、BATCH 不校验；P6 只能事后发现，且回炉目标落在一个看不到合同的节点上，于是每轮换一层皮。**这不是“模型不会写”，是上下文在层间丢失。**

---

## 3. 目标工作流（先定规则，再改代码）

> **本批不做什么（防止执行 AI 自行升级）**：不引入 agent / 工具调用 / 新 LLM 节点；不重写 VOLUMES/CHARACTERS 的 prompt 结构（只加断言）；不改前端；不改主站；不做 `direct=true` 免审；不为了过闸放宽 P6。原因：run58 的失败是“上下文在层间丢失”，不是“模型不够自主”；先把数据流修对，再谈写作 agent。

### 3.1 六条不变量（写进类注释与 PR 描述）

1. **I1 唯一写者**：只有 CONTRACT 单元写故事事实；SCHEDULE 是纯函数；其余节点只读。
2. **I2 机械投影**：日程、线程章号、章功能槽、必有拍，全部由 Java 推导，LLM 只写“怎么演”。
3. **I3 各写各层**：VOLUMES 只写卷级叙述；CHARACTERS 只写角色弧光；ROUTE 只装箱；BATCH 只写章纲文字；P6 只诊断。
4. **I4 层内失败**：谁产出谁校验；违反合同在 ROUTE/BATCH 内 `LlmJson.Bad`（重试 3 次后 fail-fast），不允许带到 P6。
5. **I5 P6 只读**：P6 不得改合同；`directive` 只是文字修改建议；合同/日程级漂移在预检即 `FAILED/CONTRACT_INVALID`。
6. **I6 最小回炉**：`DRAFT` 只清问题章（其所在窗重跑）；`PLAN_VOLUME:n` 只重排该卷；`SCALE` 才全书重建。

### 3.2 职责矩阵（执行时对照）

| 阶段 | 输入 | 可写 | 禁止 |
|---|---|---|---|
| SCALE | 用户参数/意图 | `step_status.SCAFFOLD`、scope | 写合同 |
| CONTRACT | scope + scaffold + intent | `derive_story_contract`（FROZEN） | 写章纲/卷纲 |
| SCHEDULE | contract + scaffold ranges | `derive_chapter_schedule`、`step_status.CHAPTER_SCHEDULE` | 调 LLM |
| SPINE | contract + schedule | `STORY_SPINE`、`derive_story_thread`（带章号） | 改合同章号 |
| VOLUMES | contract + schedule + spine | `derive_volume_plan`（LLM 叙述 + Java 填 trueAnswer/requiredThreads） | 改线索章号 |
| CHARACTERS | contract cast + volumes | `derive_character_plan` | 新增 cast |
| SLOT_ROUTE | schedule + volume/character | `beat_instance`、`derive_beat_pool`、`derive_chapter_contract`、`chapters.plan_contract` | 移动线索章号 |
| ⑥b HEAD A/B | contract cast | `novel.story_assets`（cast/persona） | 新增人名 |
| ⑥b HEAD C | contract | `novel.story_assets.brief`；truth=合同投影 | LLM 生成 truth |
| ⑥b BATCH | schedule + beats + contract | `chapters.draft_outline`、`beat_instance.filled_text` | 合同外线索/机关 |
| P6 | 以上全部（只读） | `GATE`、`derive_gate_review` | 改合同/日程 |
| ⑦ | 章纲 + 合同 + 事实账 | `chapters.content` | 改大纲 |

### 3.3 数据流（目标态）

```
contract(FROZEN) ──纯函数──> chapter_schedule
      │                         │
      ├─> spine/threads(带章号) ─┤
      ├─> volumes(trueAnswer=slice) ─> characters
      │                         └─> chapterSlots(schedule) ─> route
      │                                                    ├─ ensureScheduleBeats (Java 注入)
      │                                                    └─ packer(保护线索拍)
      └─> BATCH(per-chapter must lists) ─> clueEvents/closingActs 校验
                                       └─> P6(只读合同+日程, 只评质量)
```

---

## 4. 数据模型（V138，只增不改）

**File:** `novelgen-data/src/main/resources/db/migration-derive/V138__derive_chapter_schedule.sql`

```sql
CREATE TABLE derive_chapter_schedule (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    contract_version INTEGER NOT NULL,
    chapter_no INTEGER NOT NULL,
    volume_no INTEGER NOT NULL,
    reveal BOOLEAN NOT NULL DEFAULT FALSE,
    must_plant JSONB NOT NULL DEFAULT '[]'::jsonb,
    must_payoff JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT,
    updated_user_id BIGINT,
    CONSTRAINT uq_derive_chapter_schedule_run_chapter UNIQUE (run_id, chapter_no)
);
CREATE INDEX idx_derive_chapter_schedule_run ON derive_chapter_schedule(run_id, chapter_no);

ALTER TABLE derive_story_thread ADD COLUMN plant_chapter INTEGER;
ALTER TABLE derive_story_thread ADD COLUMN payoff_chapter INTEGER;
```

配套（照 `DeriveStoryContractDO/Mapper/XML` 现有写法）：

- `novelgen-model/.../entity/derive/DeriveChapterScheduleDO.java`（extends BaseDO，`@TableName("derive_chapter_schedule")`）
- `novelgen-data/.../derive/mapper/DeriveChapterScheduleMapper.java`
- `novelgen-data/src/main/resources/mapper/DeriveChapterScheduleMapper.xml`：
  - `insertFull`（JSONB 显式 `::jsonb`，`<select resultType="long">` 若需返回 id）
  - `deleteByRun(runId)`
  - `selectByRun(runId)`（按 chapter_no 升序）
  - `selectByRunAndVersion(runId, version)`
- `DeriveStoryThreadDO` 增加 `plantChapter/payoffChapter`；`DeriveStoryThreadMapper.xml` 的 `insertFull` 补两列（先 grep 现有 XML 的全部 insert 语句，避免漏改）。

**迁移纪律:** 不改 V1-V137；线上启动会跑 V138；若手工改过历史迁移，必须按 AGENTS 用 `flyway_schema_history.checksum` 修复，但本批禁止改历史。

---

## 5. 核心模型与投影

### 5.1 新 record

**File:** `novelgen-service/.../derive/contract/ChapterScheduleEntry.java`

```java
public record ChapterScheduleEntry(
        int chapterNo,
        int volumeNo,
        boolean reveal,
        List<String> mustPlant,   // clueKey 列表，按合同 clue 顺序
        List<String> mustPayoff) {}
```

### 5.2 投影器（纯函数，零依赖、可直接单测）

**File:** `novelgen-service/.../derive/contract/ChapterScheduleProjector.java`

```java
public final class ChapterScheduleProjector {
    /** ranges 必须连续覆盖 1..chapterCount；单卷传 [{1,1,N}]。 */
    public static List<ChapterScheduleEntry> project(StoryContract contract,
                                                     int chapterCount,
                                                     List<ScaffoldContract.VolumeRange> ranges);
}
```

规则（每条违反抛 `BizException(PARAM_ERROR, "CONTRACT_SCHEDULE_INVALID：...")` 或返回错误列表，二选一，但必须可测）：

1. `contract` 非空、`status=FROZEN`、`clues.size()>=3`；
2. `chapterCount` 由调用方解析：优先 `scaffold.chapterCount()`，无 scaffold 时用 `contract.scope().chapterCount()`，再不行才报错；`ranges` 优先 scaffold.volumeRanges，无 scaffold 时由已有 `derive_volume_plan` 行生成，单卷书兜底 `[{1,1,chapterCount}]`；
3. 每条 clue：`1 <= planted < payoff <= chapterCount`；
4. `revealChapter in 1..chapterCount`；
5. 每条 clue 的 `clueKey` 对应 `contract.threads()` 里同 key 的线程；`openedVolume == volumeOf(plantedChapter)` 且 `payoffVolume == volumeOf(payoffChapter)`（多卷一致性；不一致即合同自相矛盾）；
6. 每章恰好一条 entry；`mustPlant/mustPayoff` 按 clueKey 排序稳定；
7. 允许同章多条 plant/payoff（例如 11 章兑 2 条），但单章超过 3 条要 `WARN` 日志（不拦）；
8. 单卷书：所有 entry `volumeNo=1`；多卷书：由 `scaffold.volumeRanges()` 决定，范围必须连续覆盖 1..chapterCount。

### 5.3 日程服务（持久化 + 版本守卫）

**File:** `novelgen-service/.../derive/service/DeriveChapterScheduleService.java`

```java
@Service
@RequiredArgsConstructor
public class DeriveChapterScheduleService {
    public List<ChapterScheduleEntry> project(RunDO run);            // 纯投影，不落库
    public List<ChapterScheduleEntry> persist(RunDO run, List<ChapterScheduleEntry> entries); // 先删后插
    public List<ChapterScheduleEntry> load(RunDO run);               // 读表；空则返回 List.of()
    public List<ChapterScheduleEntry> require(RunDO run);            // 空则 BizException SCHEDULE_REQUIRED
    public void assertCurrent(RunDO run);                            // 表内 contract_version == 合同 version，否则 SCHEDULE_STALE
    public void deleteByRun(long runId);                             // clearPlanning / SCALE 回炉用
}
```

- `persist`：`deleteByRun` + 批量 insert（事务 `@Transactional`）；同时写
  `run.step_status.steps.CHAPTER_SCHEDULE = {"contractVersion":v,"entries":[...]}`（仅审计，读方以表为准）。
- `assertCurrent`：合同版本与表内版本不一致 → fail-fast（防止改合同后旧日程被继续使用）。
- 范围解析顺序（`project`/`persist` 共用）：`step_status.SCAFFOLD` → `derive_volume_plan` 行 → 单卷兜底 `[{1,1,chapterCount}]`；**禁止按“平均分卷”猜**。

---

## 6. 状态机：新增 SCHEDULE 单元

**Files:** `PlanContracts.java`、`DeriveStoryPlanService.java`、`PlanV2StateCodec`（无需改）、`LegacyContractCompatibilityTest`

### 6.1 枚举与顺序

```java
public enum PlanUnit {
    SCALE, CONTRACT, SCHEDULE, SPINE, VOLUMES, CHARACTERS, SLOT_ROUTE, VALIDATE, NEED_SCOPE, DONE, LEGACY
}
```

### 6.2 迁移

- `advanceContract`：末尾 next 从 `SPINE` 改为 `SCHEDULE`，`completedUnits` 追加 `CONTRACT`（现有代码 L164-178，只改这一处）。
- 新增 `advanceSchedule(RunDO, PlanV2State)`：
  1. `StoryContract contract = contractService.require(run);`
  2. `entries = scheduleService.persist(run, scheduleService.project(run));`（范围解析见 §5.3）
  3. next = `new PlanV2State(SPINE, append(completedUnits, SCHEDULE), scope, volumeUnits)`；
  4. `return planVO(run, scope, "SCHEDULE", false);`
- `advance()` switch（**DeriveStoryPlanService L84-92**）加 `case SCHEDULE -> advanceSchedule(run, state);`。
- **另一处必须同步**：`DerivePlanService.planV2`（L278-294）的 `switch (state.currentUnit())` 是把 `SCALE/CONTRACT/SPINE/VOLUMES/CHARACTERS` 一起委托给 `storyPlanService.advance` 的；把 `SCHEDULE` 加进这一组，否则枚举新增后 switch 不穷尽、编译失败（或运行时落空）。
- 全仓只有这两处 switch on `PlanUnit`（已 grep），改完再 grep 一次确认。
- `confirmScope`（L118-121）仍落 `CONTRACT`，不改。

### 6.3 旧 run 兼容

在 `advance()` 的 switch 前加 `state = ensureScheduleForLegacy(run, state);`：

- 若 `currentUnit` ∈ {SPINE,VOLUMES,CHARACTERS,SLOT_ROUTE,VALIDATE,DONE} 且 `scheduleService.load(run)` 为空 → 用当前合同投影并 persist（`log.info("旧 run 补投影章级日程...")`；范围按 §5.3 解析，旧 run 优先用已有 `derive_volume_plan` 的卷范围）；
- 若已有日程但 `contractVersion` 与合同不一致 → 重新投影覆盖（合同只在新建 run/清空时变化，出现 stale 说明状态错乱，必须告警 + 覆盖）；
- 无合同（`contractService.load==null`）→ 维持现有 `backfillLegacyContract` 行为，不在此处造日程。
- `backfillLegacyContract` 的 early-return 集合 `{SCALE, CONTRACT, NEED_SCOPE}` 加上 `SCHEDULE`（合同存在时本来也不会回退，但显式写清避免后人误解）。

### 6.4 回炉语义

- `DeriveResetService.clearPlanning`：增加 `scheduleService.deleteByRun(runId)`（与删除合同同一处）。
- `resetPlanState("SCALE")`：清 `TRUTH_CONTRACT/STORY_CONTRACT` 的同时 **清 CHAPTER_SCHEDULE**（日程表由 `clearPlanning`/`replanFrom` 的调用方删；只改 step_status 不够，注意两处都做）。
- `resetPlanState("SPINE")`：**保留**日程（它是合同投影，合同没变）。
- `DeriveAutoFlowService`：PLANNING 分支无需改（`planService.plan()` 自动推进新单元）。

### 6.5 测试

`DeriveStoryPlanContractTest` 增加：

- `contractTransitionsToScheduleBeforeSpine`：`advanceContract` 后 `currentUnit=SCHEDULE`；
- `scheduleUnitPersistsRowsAndTransitionsToSpine`：mock 合同+scaffold，验证 persist 一次、`currentUnit=SPINE`、`completedUnits` 含 SCHEDULE；
- `legacyRunGetsScheduleBackfilled`：currentUnit=CHARACTERS 无日程 → 调 advance 前补投影；
- `clearPlanningDeletesScheduleRows`（放 `DeriveResetServiceTest`）。

---

## 7. 下游改造（按数据流顺序）

### 7.1 SPINE / 线程账带章号

**File:** `DeriveStoryPlanService`

- `spineFromContract`（L344-382）：构造 `ThreadContract` 时，从合同 clue 找同 key 的 `plantedChapter/payoffChapter`，写入 `contract` map：
  ```java
  Map<String,Object> extra = new LinkedHashMap<>();
  extra.put("plantChapter", clue.plantedChapter());
  extra.put("payoffChapter", clue.payoffChapter());
  ```
  （`ThreadContract.contract` 已有字段，不改 record，避免大面积构造器改动。）
- `persistSpine`（L628-651）：`row.setPlantChapter(...)` / `row.setPayoffChapter(...)`，`row.setContract(toJson(extra))`。
- 线程表读方（route/P6）继续用 key；章号供审计与校验。

**测试:** `PlanContractConsistencyTest` 增加 `threadContractCarriesClueChapters`。

### 7.2 VOLUMES：只保留叙述字段，合同字段由 Java 填

本批**不重写 VOLUMES 提示词结构**（降低风险），只做两件事：

1. `advanceVolumes` 的输入加 `scaffold.volumeRanges`（只读），prompt 增加一句“fromChapter/toChapter 必须与输入 volumeRanges 完全一致，不得自行改分卷边界”；
2. `reconcileVolumes` 后校验每卷范围与 `scaffold.volumeRanges()` 一致，且每卷 `requiredThreads` 的每条 clue，其 plant/payoff 章都落在该卷范围内（跨卷线程允许，但必须与合同线程的 openedVolume/payoffVolume 一致）。不一致 → `PlanJson.Bad("CONTRACT_SCHEDULE_DRIFT：...")`。

> 可选（非本批必须）：后续把 `trueAnswer/requiredThreads` 从 prompt 输出里删掉，减少一次“写了再覆盖”。本批不做，避免同时改 prompt+parser。

### 7.3 SLOT_ROUTE：章功能槽由日程派生

**File:** `DeriveBeatRouteService`

1. `chapterSlots(VolumeContract volume, List<ChapterScheduleEntry> schedule)`：
   - 基础角色：`i==1 -> HOOK`、`i==2 -> CASE_SETUP`、`i==4 -> FUNCTION_USE`（通用职能戏，不是故事事实，保留）、倒数第 2 章 → `HIGHLIGHT/CHARACTER_CHOICE/COST/LOCAL_CLOSURE`、末章 → `LOCAL_CLOSURE/NEXT_HOOK`（最终卷 `FINAL_CLOSURE`）；
   - 日程角色：该章 `mustPlant` 非空 → 加 `CLUE_PLANT`；`mustPayoff` 非空 → 加 `CLUE_PAYOFF`；`reveal=true` → 加 `FINAL_REVEAL`；
   - 去重后最多保留 4 个角色；**日程角色优先保留**，超限时丢基础角色（`HOOK/HIGHLIGHT/...`），保证线索拍不被 cap 吃掉；
   - **删除**旧的固定 `i==3 -> CLUE_PLANT`、`i==count-2 -> CLUE_ECHO`（位置猜测正是漂移源头）。
   - `mergeRequiredSlots`（L557-593）与 `normalizeRouteChapters`（L534-556）里的模板/补章/cap 逻辑同样改为“日程角色优先”，并把 `ctx.schedule` 传进去；所有 `chapterSlots(volume)` 调用点（buildRouteUser L1333、normalizeRouteChapters）统一改成 `chapterSlots(volume, ctx.schedule)`。
2. `RouteContext` 增加 `List<ChapterScheduleEntry> schedule` 与 `Map<Integer,ChapterScheduleEntry> scheduleByChapter`；所有构造点（route/buildContext/测试）补齐。
3. `buildRouteUser` 增加：
   ```json
   "chapterSchedule":[{"chapterNo":3,"mustPlant":["GM-CLUE-01"],"mustPayoff":[],"reveal":false}, ...],
   "scheduleRules":["mustPlant 的线索必须在本章安排 CLUE_PLANT 拍且 threadRefs 含该 key；mustPayoff 必须安排 CLUE_PAYOFF；其他拍不得引用线索 key（REL-* 除外）；CLUE_ECHO 只允许在 plant<payoff 之间"]
   ```
   同时把 `FROZEN_CONTRACT`（`contractService.promptContext`）放进输入（目前 route 完全没有合同）。
4. 解析后机械处理（顺序固定，写进注释）。**注意 `parseRoute` 自己在 L412 就会跑一次 `validateRoute`，所以 schedule 注入必须在 parse 内先做，否则会在重试里空转：**
   ```
   parseRoute 内：
     ... 现有 synthesizeMissingRoles / ensureRequiredThreadPayoffs
     → normalizeClueThreadRefs(plan, ctx.schedule)   // 新增（幂等）
     → ensureScheduleBeats(plan, ctx.schedule)       // 新增（幂等）
     → validateRoute(plan, ctx)                      // 现有 L412
   routeVolume 外层（pack 之后）：
     → DeriveChapterPacker.pack（保护线索拍）
     → rebalanceRouteLoad（保护线索拍）
     → normalizeClueThreadRefs + ensureScheduleBeats // 再跑一次，防 pack/rebalance 破坏
     → validateRoute                                 // 现有 L130
   ```
   > `normalize/ensure` 必须幂等（已存在则不重复加）；`synthesizeMissingRoles` 对 `CLUE_PLANT/CLUE_PAYOFF/FINAL_REVEAL` 三个角色**跳过**，这三个角色只由 `ensureScheduleBeats` 生成（它们必须带 threadRefs，通用补拍不带）。
5. `normalizeClueThreadRefs` 规则：
   - 某拍所在章 = clue 的 plantedChapter 或 payoffChapter → 允许该 key；
   - 某拍 `beatRole=CLUE_ECHO` 且 `plantedChapter < chapter < payoffChapter` → 允许；
   - 其余章引用该 clue → 删除并 `log.warn`（不整轮拒绝）；
   - 同章同 key 的重复 plant/payoff 拍：保留第一个，其余剔除该 key（拍本身保留，避免改变拍数）；
   - `REL-*` 一律保留。
6. `ensureScheduleBeats` 规则：
   - 对每条 clue：
     - 若 plant 章没有“`CLUE_PLANT` 且 threadRefs 含 key”的拍 → 新增 `SyntheticAssignment(chapterNo=plantedChapter, storyLayer=LOCAL_CASE, beatRole=CLUE_PLANT, beatType="铺垫", goal="埋设线索 "+key+"："+clueText, focusCharacters=[ownerKey], threadRefs=[key], appearanceMode=LIVE)`；
     - 若 payoff 章没有“`CLUE_PAYOFF` 且含 key”的拍 → 新增 `CLUE_PAYOFF`（beatType="收束"，goal="兑现线索 …"）。
   - 新增拍由 `persistRoute` 分配 seq（复用 `nextSeq`），并写入 `derive_beat_pool`（现有 `persistPoolAndContracts` 已覆盖 synthetic）。
7. 保护：`DeriveChapterPacker.protectedRole` 增加 `CLUE_PLANT`/`CLUE_PAYOFF`/`FINAL_REVEAL`；`rebalanceRouteLoad` 的“可搬”判定增加“threadRefs 含 clue key 的拍不可搬”。
8. `validateRoute` 增加（新错误码）：
   - `ROUTE_CLUE_PLANT_MISSING`：每条 clue 在 plant 章必须恰有 1 个 `CLUE_PLANT` 拍且含 key；
   - `ROUTE_CLUE_PAYOFF_MISSING`：同上，payoff 章；
   - `ROUTE_CLUE_STRAY`：任何拍在非允许章引用 clue key（normalize 后应恒空，作为 assert）；
   - `ROUTE_REVEAL_CHAPTER`：`reveal=true` 的章必须有 `FINAL_REVEAL` 拍。

**测试:** `DeriveBeatRouteServiceTest` 增加 `chapterSlotsFollowSchedule`、`strayClueRefsRemoved`、`missingScheduleBeatsInjected`、`packerKeepsClueBeats`；`DeriveChapterPackerTest` 增加 `doesNotMoveClueBeats`。

### 7.4 ⑥b HEAD C：不再让 LLM 写真相卡

**File:** `DeriveDraftService`

- `HEAD_C_PROMPT`（L126-150）删除 `truth` 段，改成：
  ```
  只输出 {"brief":{"premise":"…","ending":"…"}}。
  真相卡、真凶、动机、手法、线索章号一律由冻结合同决定，你不输出、也不得改写。
  ```
- `headStep` stage==2（L496-540）：
  - 删除 `validateHeadC` 调用与降级分支；`llmJson.ask` 的解析器只做 `parseBriefTruth`（只要求 premise），并删除其中的 `normalizeSolution/truthIssues` 调用（truth 不再由 LLM 产出）；
  - `headStage` 的 `truth.solution.culprit` 判定保留：truth 紧接着用合同投影写入（形状含 `solution.culprit`，见 `frozenTruthCard`），不会卡在第 2 段；无合同旧 run 仍走原来的 LLM truth 回退；
  - `truth` 永远取 `contractService.frozenTruthCard(run)`（无合同时才回退 `out.truth` 兼容旧 run）；
  - 日志改为 `有真相卡={truth != null}`。
- 删除 `validateHeadC` 方法及 `DeriveDraftContractTest.headCCannotChange*` 两个用例，改为 `headCDoesNotWriteTruth`（断言 novels.story_assets.truth 等于合同投影、LLM 输出里没有 truth 也不影响落库）。
- `HEAD_B_PROMPT` 里对 `TruthContract` 的引用保留（只读）。

**收益:** 每次 run 省 3 次失败调用 + 1 次补调（run58 实测 3107-3110），并消灭 `CONTRACT_HEAD_C_DRIFT` 这一类假失败。

### 7.5 ⑥b BATCH：按章日程写，结构自证 + 硬校验

**Files:** `DeriveDraftService`（prompt、`buildBatchUser`、`parseBatch`、新增 `validateSchedule`）

1. `buildBatchUser`（L834-942）在每章节点加：
   ```json
   {"no":3,"title":"…","budget":1200,
    "mustPlant":[{"key":"GM-CLUE-01","text":"走廊天花板…擦痕","ownerKey":"C1"}],
    "mustPayoff":[],
    "reveal":false}
   ```
   `mustPlant/mustPayoff` 取自 `scheduleByChapter`，文本取合同 clue。
2. `BATCH_PROMPT` 增加规则 8-11（放在现有 7 之后）：
   ```
   8. 章级日程是硬约束：mustPlant 的每条线索必须在**本章** draft_outline 写出可观测现象（不写结论），
      并在 clueEvents 记 {"key":"…","act":"PLANT"}；mustPayoff 必须写出兑现动作并记 act=PAYOFF；
      其他章不得复述这些线索现象，禁止提前埋设/重复兑现。
   9. reveal=true 的**这一章**必须用 closingActs 覆盖 CULPRIT/MOTIVE/METHOD 三项，并在 draft_outline
      里逐字写出 culpritName；其后的章只写收束/余韵，可只带其中若干项；reveal 之前不得出现真凶姓名+手法结论。
   10. 不得新增合同 clue 之外的证据/机关/线人；需要障碍时用无名环境描写（如“一块铁板”→改为“一处障碍”）。
   11. 输出 clueEvents/closingActs；没有就空数组，禁止省略。
   ```
   输出 schema 在 chapters 元素里加 `"clueEvents":[{"key":"GM-CLUE-01","act":"PLANT"}]`、`"closingActs":["CULPRIT","MOTIVE","METHOD"]`。
3. `parseBatch` 之后调用 `validateScheduleEvents(parsed, window, schedule, contract)`（读 `normalizeBatchOutput` 深拷贝后的节点，字段不得被后续解析丢掉）：
   - 逐章：`PLANT` keys 集合 == `mustPlant`；`PAYOFF` keys 集合 == `mustPayoff`；无其他 key、无重复 act；
   - `closingActs`：`chapter < reveal` 必须为空；`reveal` 章集合 == `{CULPRIT,MOTIVE,METHOD}`；
   - `reveal` 章 `draft_outline` 必须逐字包含 `culpritName`；
   - 任何违反 → `throw new LlmJson.Bad("CONTRACT_CLUE_DRIFT：…")`（带具体章/键），让 `LlmJson.ask` 喂回重试 3 轮；3 轮仍失败 → 该窗失败（run 停在 PLANNING/DRAFTING，不再进 P6）。
   - 可选：把 `clueEvents/closingActs` 合并写进 `chapters.plan_contract`（读旧值再 merge），供 P6/审计查询；不写也不影响本批通过标准。
4. 保留现有 `validateBatch` 的人名/合同外编号/揭示前泄底检查；删除 `planUsesDifferentTruth` 残留（若还有）。

**测试:** `DeriveDraftContractTest` 增加 `batchClueEventsMustMatchSchedule`、`batchCannotPlantEarly`、`batchClosingActsRequiredAfterReveal`、`batchCulpritNameMustAppear`；`DeriveDraftBatchRepairTest` 增加 `clueEventsSurviveNormalize`。

### 7.6 P6：只读、预检、不可改合同

**Files:** `DeriveGateService`、`StoryContractValidator`、`DerivePlanValidationService`

1. `GateTruth` 增加 `List<ChapterScheduleEntry> schedule`；`resolveGateTruth` 用 `scheduleService.load(run)`（**不要 require**，缺失由第 3 步判终态，避免抛裸异常），并在非空时 `assertCurrent(run)`。
2. `buildUser` 增加 `chapterSchedule`（只读）与规则：“日程与合同是硬约束；你的 directive 只能描述章纲文字重写，不得要求移动线索章号、改真凶/手法、增删角色”。
3. `contractIssues` 扩展（**只放“上游违约”类，命中即终态**）：
   - 日程缺失/版本不一致 → `CONTRACT_INVALID`；
   - 拍里 clue 引用不在 plant/payoff/echo 允许章，或缺 plant/payoff 拍 → `CONTRACT_INVALID`；
   - 章纲 `culpritName` 未出现在 `[reveal..end]` **不放这里**，放 `preflightIssues`（`DRAFT` 目标，允许重写文字修复）。
4. **修 preflight 不阻塞 bug**（L219-229 + L570-595）：`review.criteria()` 为空但 `review.issues()` 非空时，`decideGate` 必须返回 `REWORK`（目标取 `planCheck` 的最上游 hard error，缺省 `DRAFT`），不能返回 `GO`。加单测 `preflightIssuesBlockGate`。
5. gate prompt（L72-125）加一句：“合同/日程冲突只诊断，不得在 directive 中改写；directive 提到的章号必须是本书已有章；不得出现‘把线索改到第 X 章’这类改合同的指令”。
6. `decideGate` 的熔断规则保持 6 轮（`prevRound>=5`），但**合同级**（`CONTRACT_INVALID`）不进轮次循环，直接 FAILED。

### 7.7 回炉：按 target 作用域，禁止整本清

**File:** `DeriveDraftService`

替换 `draftInner` 的 REWORK 分支（L282-296）为：

```java
if ("REWORK".equals(run.getStatus())) {
    ResetScope scope = resetForRepair(run);          // 新增
    return new DraftVO(runId, novelId, "", "", 0, 0, 0, "RESET", 0, scope.windows(), false);
}
```

`resetForRepair(RunDO run)`：

1. 读 `repairTarget = DeriveGateService.repairTarget(stepStatus)`；
2. `PLAN_VOLUME:n` → 不在这里做（auto flow 已走 `replanVolume + resetVolumeDraft`）；若走到这里，抛错避免双路径；
3. `DRAFT`：
   - 从 GATE `issues` + `directive` 里抽章号：
     - 区间：`第\s*(\d+)\s*[~\-至]\s*(\d+)\s*章` → 展开；
     - 单点：`第\s*(\d+)\s*章`；
     - 英文：`ch(?:apter)?\s*(\d+)`；
     - 去重、夹到 `1..chapterCount`，越界丢弃；
   - 抽不到 → 全章（fallback，`log.warn`）；抽到的章数超过全书一半 → 也回退全章（窗重跑反正覆盖大半，避免复杂）；
   - 只清这些章的 `draft_outline`（新 XML `clearDraftOutlinesByChapters(novelId, chapterNos)`）与这些章的 `beat_instance.filled_text/status`（新 mapper 方法 `resetFilledByChapters(runId, chapterNos)`）；
   - **不清** `novel.story_assets`（cast/brief/truth 都来自合同，清了只会重跑 HEAD 且增加漂移）；
   - `runMapper.updateStatus(runId,"PLANNING")`；保留 `GATE` 轮次/指令（不 `gateReset`）；
   - 返回 `ResetScope(affectedChapters, 受影响窗数)`，日志写清楚“局部回炉：章 X..Y，其它章保留”。
4. `SPINE/SCALE` 不走这里（auto flow 已处理）。
5. `DeriveAutoFlowService` REWORK 分支（L188-215）：`DRAFT` 分支改为先 `draftService.resetForRepair(auto.runId)` 再 `continue`，不要让 `draft()` 在 status=REWORK 下再全清一次；`PLAN_VOLUME` 分支保持现有 `replanVolume + resetVolumeDraft`，但 `replanVolume` 现在会带日程（T7.3）。

**测试:** `DeriveResetServiceTest` 增加 `draftRepairOnlyClearsAffectedChapters`、`draftRepairKeepsCastAndBrief`；`DeriveAutoFlowServiceTest`（若无则新建）增加 `draftTargetDoesNotClearWholeBook`。

---

## 8. 提示词改动（逐字对照，改完跑 arity 测试）

> `derive_contract` 的提示词在 `StoryPlanningPrompts.CONTRACT` 与 `PromptCatalog.ALL` 两处逐字一致（`ContractPromptArityTest` 钉着）。**改任何一处必须同步另一处**，否则启动/测试失败。

### 8.1 `derive_contract`（只加“自检”一段，不改 JSON schema）

在要求 6 之后加：

```
7. 自洽自检（冻结前必须逐条核对）：
   - method 里“谁做了什么”必须能解释每条 clue 的现象；不得出现“某人抄录”与“笔迹不是他”并存；
   - finalTruth 对方法的描述必须与 method 同义，不得一个说“抄录”、一个说“出自某人手笔”；
   - 若 clue 依赖某人的笔迹/字迹，method 必须写清该笔迹为何偏离其平日（例如失控状态下誊抄），
     禁止留下“抄录/代笔”二义。
```

同步 `PromptCatalog` 的 `derive_contract` 文本；跑 `ContractPromptArityTest`。

### 8.2 `ROUTE`（新增 11-14 条）

```
11. 输入 chapterSchedule 是硬约束：mustPlant 的线索必须在**该章**安排 CLUE_PLANT 拍，
    threadRefs 含该 key；mustPayoff 必须安排 CLUE_PAYOFF 拍；缺拍 Java 会补，但补出来的拍内容固定，不如你自己安排；
12. 除该章 mustPlant/mustPayoff 外，任何拍不得引用线索 key（REL-* 关系线例外）；
    CLUE_ECHO 只允许出现在 plantedChapter < chapter < payoffChapter；
13. 不得新增合同外线索/机关/证据；障碍用无名环境描写；不得改真凶/动机/手法；
14. reveal 章（chapterSchedule.reveal=true）必须有 FINAL_REVEAL 拍。
```

### 8.3 `BATCH`（见 §7.5 规则 8-11 + schema）

### 8.4 `HEAD_C`（见 §7.4）

### 8.5 `GATE`（只加一句约束）

在“你的职责是诊断”之后加：

```
合同与章级日程是只读事实：不得在 directive 中要求移动线索章号、改真凶/动机/手法、增删角色；
directive 只能描述“重写第 X 章的哪些句子”。
```

---

## 9. 任务分解（按顺序执行；每个 Task 先失败测试）

### T0 基线（0.5h）
- [ ] 读文件（§0）；`git status`；`mvn -q test` 记录基线。
- [ ] 把本 plan 的 §2.2 表格逐行核对到当前代码（行号可能漂移，以方法名为准）；发现不一致先修正 plan 再动手。

### T1 V138 迁移 + DO/Mapper（1h）
- [ ] 写 `V138__derive_chapter_schedule.sql`（§4，含 thread 两列）。
- [ ] 生成/手写 `DeriveChapterScheduleDO`、Mapper 接口、XML（insertFull/deleteByRun/selectByRun/selectByRunAndVersion；JSONB `::jsonb`）。
- [ ] `DeriveStoryThreadDO` 加两字段；XML insertFull 补列（grep 所有 insert）。
- [ ] 编译：`mvn -q -DskipTests compile`。
- **验收:** 本地起服务（或 `flyway` 单测）能建表；`\d derive_chapter_schedule` 与 DDL 一致。

### T2 投影器 + 单测（2h）
- [ ] `ChapterScheduleEntry`、`ChapterScheduleProjector`（§5.1/5.2）。
- [ ] 测试 `ChapterScheduleProjectorTest`：
  - `projectsPlantPayoffAndReveal`（6 clue/13 章，逐章断言）；
  - `rejectsClueOutOfRange`；`rejectsPayoffBeforePlant`；
  - `rejectsThreadVolumeMismatch`（clue 章号与 thread 卷号不一致）；
  - `multiVolumeRangesCoverAllChapters`；
  - `singleVolumeAllVolumeOne`。
- **验收:** `mvn -q test -pl novelgen-service -am "-Dtest=ChapterScheduleProjectorTest" "-Dsurefire.failIfNoSpecifiedTests=false"` 绿。

### T3 SCHEDULE 单元（2h）
- [ ] `PlanUnit.SCHEDULE`；`advanceContract`→SCHEDULE；`advanceSchedule`；**两处 switch 同步**（`DeriveStoryPlanService.advance`、`DerivePlanService.planV2`）；旧 run 补投影（§6）。
- [ ] `DeriveChapterScheduleService`（§5.3），`persist` 事务 + step_status 快照。
- [ ] 测试：`DeriveStoryPlanContractTest` 4 个新用例（§6.5）。
- **验收:** 定向测试绿；本地用 mock run 走 `advance` 能看到 `currentUnit` 顺序 SCALE→CONTRACT→SCHEDULE→SPINE。

### T4 SPINE 线程带章号（1h）
- [ ] §7.1；测试 `PlanContractConsistencyTest.threadContractCarriesClueChapters`。
- [ ] grep `DeriveStoryThreadMapper.xml` 的所有列清单（insert/select/resultMap），补 `plant_chapter/payoff_chapter`；DO 加字段。
- **验收:** `derive_story_thread`（新 run）plant/payoff 列与合同一致（本地/线上 SQL 都查）。

### T5 ROUTE 章功能槽 + 输入日程（3h）
- [ ] `chapterSlots(volume, schedule)`；`RouteContext` 加日程；`buildRouteUser` 加 `chapterSchedule/scheduleRules/FROZEN_CONTRACT`；prompt §8.2。
- [ ] 测试 `DeriveBeatRouteServiceTest.chapterSlotsFollowSchedule`（ch3/4/5/6/8/9 有 CLUE_PLANT，ch10-13 有 CLUE_PAYOFF，reveal 章有 FINAL_REVEAL）。
- **验收:** 定向测试绿；旧用例（位置模板）同步改掉，不要保留双重语义。

### T6 机械注入 + 归一 + 保护（4h）
- [ ] `normalizeClueThreadRefs`、`ensureScheduleBeats`、packer/`rebalanceRouteLoad` 保护、`validateRoute` 新错误码（§7.3 4-8）。
- [ ] 测试：`strayClueRefsRemoved`、`missingScheduleBeatsInjected`、`packerKeepsClueBeats`、`validateRouteFailsWhenPlantMissing`。
- **验收:** 用 run58 的合同 + scaffold 做 fixture（可放测试资源 JSON），投影后 route 结果满足“每 clue 恰好 1 plant + 1 payoff + 无 stray”。

### T7 BATCH 日程约束（4h）
- [ ] `buildBatchUser` 每章 must 列表；prompt §7.5；`parseBatch` 后 `validateScheduleEvents`。
- [ ] 测试：4 个新用例（§7.5）+ 旧 `DeriveDraftContractTest` 里与 truth 相关的用例改掉。
- **验收:** 定向测试绿；构造“CLUE-06 埋到 ch8”的假输出必须被拒（`CONTRACT_CLUE_DRIFT`）。

### T8 HEAD C 去 truth（1.5h）
- [ ] §7.4；删除 `validateHeadC` 与两个旧测试；新增 `headCDoesNotWriteTruth`。
- **验收:** 定向测试绿；grep `validateHeadC` 为 0；`HEAD_C_PROMPT` 不再含 `truth`。

### T9 P6 预检 + 阻塞 bug（2.5h）
- [ ] `GateTruth.schedule`；`buildUser` 日程；`contractIssues` 扩展；`decideGate` preflight 阻塞修复；gate prompt 约束（§7.6）。
- [ ] 测试 `DeriveGateContractTest`：`gateInputHasOneSchedule`、`scheduleDriftFailsBeforeLlm`（`CONTRACT_INVALID` 终态）、`preflightIssuesBlockGate`、`directiveCannotChangeSchedule`（用含“第8~9章”的 directive，断言不写回合同/日程）。
- **验收:** 定向测试绿；本地构造缺 plant 拍的 plan，gate 返回 `FAILED/CONTRACT_INVALID` 且不进 LLM（mock 校验 `llmJson` 未被调用）。

### T10 回炉作用域（3h）
- [ ] `resetForRepair` + 新 mapper 方法 `clearDraftOutlinesByChapters`/`resetFilledByChapters`；`draftInner` REWORK 分支；auto flow DRAFT 分支（§7.7）。
- [ ] 测试：`draftRepairOnlyClearsAffectedChapters`、`draftRepairKeepsCastAndBrief`、`draftRepairFallsBackToAllWhenNoChapter`。
- **验收:** 单测里 mock 4 窗，断言只有问题章所在窗的 outline 被清，其它章保留。

### T11 提示词收尾与清理（1h）
- [ ] `CONTRACT` + `PromptCatalog` 同步（§8.1）；`ContractPromptArityTest` 绿。
- [ ] `StoryPlanningPrompts.SPINE` 标记 `@Deprecated`（不删，避免 catalog/测试误伤）；grep 确认无调用点。
- [ ] 清理 `validateBatch` 里重复的旧逻辑、死变量；不要顺手重构无关代码。

### T12 文档 + 全量测试与构建（1h）
- [ ] 先改文档（AGENTS 要求“改契约先改文档”）：`docs/architecture/pipeline-contracts.md §五` 增补 `SCHEDULE` 单元、`derive_chapter_schedule` 表、`clueEvents/closingActs` 输出契约；`docs/STATUS.md` 追加本节（不改旧节）。
- [ ] 全量测试与构建：
```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
mvn -q -pl novelgen-derive-app -am package -DskipTests
```
- [ ] 全绿；jar SHA256 记录到 STATUS；`git status` 只含本批预期文件。

### T13 部署 + run59 验收（需用户当轮明确授权）

**Preconditions:** 用户当轮说“部署/上线”；线上无运行中管线；记录旧 jar SHA256。

```powershell
$K="keys\101.126.22.219_id_ed25519"; $H="root@101.126.22.219"
ssh -i $K $H "cp -a /www/wwwroot/novelgen-derive/novelgen-derive-app.jar /www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-schedule-20261028"
ssh -i $K $H "systemctl stop novelgen-derive"
scp -i $K novelgen-derive-app\target\novelgen-derive-app-0.1.0-SNAPSHOT.jar ${H}:/www/wwwroot/novelgen-derive/novelgen-derive-app.jar
ssh -i $K $H "chown www:www /www/wwwroot/novelgen-derive/novelgen-derive-app.jar; systemctl start novelgen-derive"
ssh -i $K $H "systemctl is-active novelgen-derive; ss -tlnp | grep 8091; grep -E 'Current version|Successfully applied' /www/wwwroot/novelgen-derive/logs/app.log | tail -5"
```

期望：`active`、8091、Flyway `Current version 138`、无启动异常。

**创建 run59（与 run58 同 fixture，只换 seed）：**

```bash
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs' \
  -H 'content-type: application/json' \
  -b 'novelgen_token=<当次登录 token>' \
  --data-raw '{"seed":450059,"wordsMin":12000,"wordsMax":18000,
    "tags":["悬疑","推理"],"pace":"0.75","form":"SHORT_STANDALONE","outcome":"闭合",
    "antiRepro":true,
    "intent":"现代都市单案短篇：一名调查者在一栋旧楼里调查一桩失踪案，只有三名角色，地点限于旧楼和走廊，结尾真相闭合，无死亡、超自然、第二案件。"}'
```

启动与轮询：

```bash
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs/59/auto?direct=false' -b 'novelgen_token=…'
curl -s 'https://zzdzz.cn:8888/derive/api/derive/runs/59/auto-progress' -b 'novelgen_token=…'
```

**断言 SQL（用 `-c` 分条执行；中文参数先落文件，避免引号坑）：**

```sql
-- 合同只有一行且 FROZEN
SELECT version,status,jsonb_array_length(character_slots->'cast') cast,
       truth_kernel->>'culpritName' culprit,truth_kernel->>'revealChapter' reveal
FROM derive_story_contract WHERE run_id=59 ORDER BY version;
-- 日程与合同逐条一致（plant/payoff 章号）
SELECT chapter_no,volume_no,reveal,must_plant,must_payoff
FROM derive_chapter_schedule WHERE run_id=59 ORDER BY chapter_no;
-- 线程带章号
SELECT thread_key,plant_chapter,payoff_chapter FROM derive_story_thread WHERE run_id=59 ORDER BY thread_key;
-- 拍是否只在允许章引用线索
SELECT chapter_no,seq,beat_role,route_contract->'threadRefs'
FROM beat_instance WHERE run_id=59 AND route_contract->'threadRefs' <> '[]'::jsonb
ORDER BY chapter_no,seq;
-- P6 轮次与结论
SELECT round,verdict,action,repair_target,jsonb_array_length(issues) n
FROM derive_gate_review WHERE run_id=59 ORDER BY round;
-- 阶段顺序（step_status）
SELECT step_status->'steps'->'PLAN_V2'->>'currentUnit' unit,
       step_status->'steps'->'PLAN_V2'->'completedUnits' units,
       step_status->'steps'->'GATE'->>'round' gate_round,
       step_status->'steps'->'GATE'->>'status' gate_status
FROM run WHERE id=59;
```

**通过标准：** `derive_gate_review` 只有 round=1 且 `verdict=GO/PASS`；`unit=DONE`；合同 1 行；无 `CONTRACT_INVALID`。
**停止规则：** 若 round1 `REWORK`：立即 `POST /auto/kill`（`/auto/stop` 不等当前步），收集 §10.4 证据，**不要**继续跑第 2 轮、不要改代码热修、不要 `direct=true`。

---

## 10. 测试矩阵与验收证据

### 10.1 新增/修改测试类

| 测试类 | 覆盖 |
|---|---|
| `ChapterScheduleProjectorTest`（新） | 投影规则、范围/卷一致性、多卷 |
| `DeriveChapterScheduleServiceTest`（新） | persist 事务、版本守卫、空表 require |
| `DeriveStoryPlanContractTest` | CONTRACT→SCHEDULE→SPINE、旧 run 补投影 |
| `PlanContractConsistencyTest` | 线程带章号、卷范围/日程一致 |
| `DeriveBeatRouteServiceTest` | 章功能槽派生、stray 归一、缺拍注入、保护 |
| `DeriveChapterPackerTest` | 线索拍不被搬 |
| `DeriveDraftContractTest` | clueEvents/closingActs、HEAD C 不写 truth |
| `DeriveDraftBatchRepairTest` | 归一后 clueEvents 不丢 |
| `DeriveGateContractTest` | 日程预检、preflight 阻塞、directive 不改合同 |
| `DeriveResetServiceTest` | clearPlanning 删日程；DRAFT 局部清稿 |

### 10.2 定向命令

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am `
  "-Dtest=ChapterScheduleProjectorTest,DeriveChapterScheduleServiceTest,DeriveStoryPlanContractTest,PlanContractConsistencyTest,DeriveBeatRouteServiceTest,DeriveChapterPackerTest,DeriveDraftContractTest,DeriveDraftBatchRepairTest,DeriveGateContractTest,DeriveResetServiceTest,ContractPromptArityTest,StoryContractValidatorTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

### 10.3 全量与构建

`mvn -q test`；`mvn -q -pl novelgen-derive-app -am package -DskipTests`；jar SHA256 写入 `docs/STATUS.md`（追加一节，不改旧节）。

### 10.4 失败时的首个分歧点定位（按顺序查）

1. `run.step_status.steps.PLAN_V2.completedUnits` 是否含 `SCHEDULE`；`derive_chapter_schedule` 是否 13 行；
2. `derive_story_thread.plant_chapter/payoff_chapter` 是否与合同一致；
3. `beat_instance` 里每条 clue 的 plant/payoff 拍章号是否正确（`route_contract->'threadRefs'`）；
4. `chapters.draft_outline` 的 `clueEvents`（若落库）或 `llm_call_log` 里 BATCH 的 response 是否匹配；
5. `derive_gate_review` 的 issues 分类：合同/章号类 = 实现 bug（回 T2-T9）；纯内容类（重复、节奏、动机）= 提示词/模型问题（下一批，不属本 plan 的代码验收）。

---

## 11. 回滚

- 恢复 `/www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-schedule-20261028`，`systemctl restart novelgen-derive`。
- V138 是**只增**（新表 + 两列），回滚 jar 不需要回滚库；旧代码不读新表/列，安全。
- 新 run59 是独立 run，不影响历史 run；失败 run 保留作证据，不删。

---

## 12. 风险与“不保证”

1. **P6 是 LLM，概率性**：本批能让“合同/日程/章号/clue 重复/结局三要素缺失”这些**结构类**问题在 P6 前消失；但文笔/节奏/动机铺垫仍由 LLM 评审，不能 100% 保证一次过。若 round1 只报内容类，属于下一批提示词调优，不是本批代码缺陷。
2. **ROUTE/BATCH 的 LLM 可能不配合**：Java 注入/归一保证“必须有的拍存在”，但章纲文字仍需模型写；3 轮重试后 fail-fast，run 会停在 ⑥b 而不是 P6——这是预期行为（更早暴露）。
3. **多卷书**：本批投影逻辑覆盖多卷（卷范围来自 scaffold），但线上只有单卷 fixture 实跑；多卷需补单测 fixture，不能靠线上试。
4. **历史 run**：旧 run 补投影后若合同 clue 章号本身有歧义（如 run58 的 method 二义），仍可能在 P6 内容判定上失败；**旧 run 不用于验收**，只验证“代码能补投影、不炸”。
5. **不该做的事**：不要为了让 P6 过而放宽 gate 判据、不要 `direct=true`、不要把 `CONTRACT_INVALID` 改成 `REWORK`、不要在 gate 里让 LLM 改合同。

---

## 13. Definition of Done

- [ ] V138 迁移可重复执行；`derive_chapter_schedule` 表结构与 DDL 一致。
- [ ] `ChapterScheduleProjector` 单测覆盖单卷/多卷/越界/卷不一致。
- [ ] PLAN_V2 顺序为 `SCALE→CONTRACT→SCHEDULE→SPINE→VOLUMES→CHARACTERS→SLOT_ROUTE→VALIDATE`。
- [ ] 新 run 的 `derive_chapter_schedule` 与合同声明的每条 clue 的 plant/payoff、reveal 章完全一致（条数以合同为准）。
- [ ] 新 run 的每条 clue 在 `beat_instance` 里恰好 1 个 plant 拍（plantedChapter）+ 1 个 payoff 拍（payoffChapter），无 stray。
- [ ] `chapters.draft_outline` 的 `clueEvents` 与日程一致（若按 §7.5 可选落库则查 `plan_contract`，否则查 `llm_call_log` 的 BATCH response）；无重复埋点；无合同外机关。
- [ ] HEAD C 不再产生 truth；`validateHeadC` 删除；每次 run 少 3 次无效调用。
- [ ] P6 预检覆盖日程；`CONTRACT_INVALID` 终态可触发；preflight issues 能阻塞。
- [ ] DRAFT 回炉只清问题章（其所在窗重跑），不清 cast/brief。
- [ ] 全量 `mvn -q test` 绿；derive jar 构建通过。
- [ ] （授权后）宝塔部署 active/8091/Flyway 138；run59 P6 round1 GO/PASS。
- [ ] `docs/STATUS.md` 追加本批记录（未授权不 commit）。

---

## 附录 A：run58 证据快照（只读，不要恢复）

- run58：seed=450058，novel=121，13 章，0 正文；合同 1 行 `1 FROZEN`；cast 3；culprit=陆文柏；reveal=11；clues=6/threads=6。
- 合同 clue 章号：01=3→10，02=5→11，03=6→11，04=4→12，05=8→12，06=9→13。
- 拍 threadRefs（节选）：ch1 HOOK=CLUE-06；ch2 CASE_SETUP=CLUE-04；ch3 CLUE_PLANT=CLUE-04；ch5=CLUE-03；ch8=CLUE-05；ch9=CLUE-06；ch10=CLUE-01；ch11=CLUE-02/03/04/05。
- 章纲实际：ch2 收据（应 4）；ch5 压痕（应 6）；ch8 发带（应 9）；ch9 重复发带；ch12 又写字迹（应 11）；ch6 铁板封窗（合同无）。
- P6：r1 11 项→`PLAN_VOLUME:1`；r2 8 项→`DRAFT`；r3 于 00:45:16 起、00:46:12 被 kill（`llm_call_log` 3115）。
- 结论：合同/真凶未漂移；漂移发生在“合同→章级计划→章纲”的投影链。

## 附录 B：关键代码索引（行号会漂，用方法名搜索）

- 合同：`DeriveContractService.generateIfAbsent/load/require/promptContext/frozenTruthCard`
- 规划：`DeriveStoryPlanService.advance/advanceScale/advanceContract/advanceSpine/advanceVolumes/advanceCharacters/spineFromContract/persistSpine`
- 路由：`DeriveBeatRouteService.routeVolume/buildRouteUser/chapterSlots/validateRoute/persistRoute/rebalanceRouteLoad`
- 装箱：`DeriveChapterPacker.pack/protectedRole`
- 具体稿：`DeriveDraftService.draftInner/headStep/buildBatchUser/parseBatch/normalizeBatchOutput/validateBatch/resetVolumeDraft`
- 闸：`DeriveGateService.gate/decideGate/contractIssues/buildUser/resolveGateTruth`
- 校验：`StoryContractValidator.validate/validateAgainstPlan`、`DerivePlanValidationService.validateBook/validateTruthMetadata`
- 回炉：`DeriveResetService.replanFrom/clearPlanning/resetPlanState`
- 全流程：`DeriveAutoFlowService.run`

## 附录 C：常用命令

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test
mvn -q -pl novelgen-derive-app -am package -DskipTests
```

```bash
# 线上查库（先 \d 再 SQL，禁止猜列名）
ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219 \
  "docker exec zzdzz-pg-zzdzz-pg-1 psql -U postgres -d derive_gen -c '\\d derive_chapter_schedule'"
```

```bash
# 强停（第 1 轮未过时立即执行）
curl -s -X POST 'https://zzdzz.cn:8888/derive/api/derive/runs/59/auto/kill' -b 'novelgen_token=…'
```
