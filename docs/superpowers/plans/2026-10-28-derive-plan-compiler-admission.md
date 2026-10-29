# DeriveFlow 计划编译与准入 Implementation Plan

> **Superseded:** 本文件的任务编号与跨 Task 依赖已废弃。新的主执行计划见 [2026-10-29-derive-refactor-single-pass-plan.md](2026-10-29-derive-refactor-single-pass-plan.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Execute after `2026-10-28-derive-output-contract-hardening.md`.

**Goal:** 用 Java 确定性编译卷、章、拍、场景、知识和状态计划，形成不可变 `PlanSnapshot`，并在写正文前完成硬审计和计划准入。

**Architecture:** `BeatAssignmentSolver` 负责拍与章节的稳定分配，`ContinuitySimulator` 负责逐章知识/状态回放，`StoryPlanCompiler` 负责合并合同、日程、叙事扩写和结构图，`PlanAuditor` 只读审计，`PlanAdmissionService` 将 DRAFT snapshot 提升为 ADMITTED。P6 只作为计划准入兼容适配器。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、Jackson、JUnit 5。

**Spec:** `docs/superpowers/plans/2026-10-28-derive-typed-plan-compiler-refactor.md`。

## Global Constraints

- 必须完成输出协议子计划后再开始本计划。
- 未获用户当轮允许，禁止 commit/push/deploy。
- 新增迁移从 `V139` 开始，不改历史迁移。
- `SQL` 只能在 data mapper XML；JSONB 必须显式 `::jsonb`。
- `PlanSnapshot` 是计划唯一事实源，`step_status` 只能作为兼容投影。
- 模型不得生成 `beatRole`、章节号、beat key、thread/clue/person key。
- `ADMITTED` 后计划不可变；正文阶段不得修改 revision。
- P6 不能自由改写合同/日程。

## Review Focus

- 固定线索章、reveal 章、最终闭合拍不能被 solver 移动。
- 知识读取必须早于写入来源；同一人物的状态变化必须按章序回放。
- 单章、多卷和跨卷线程必须有相同语义。
- snapshot revision、contract version 和 hash 必须一致。
- run59 的五类问题必须在正文前被准确分类。

## Canonical Error Codes

执行者必须使用以下稳定错误码，不得临时造同义词：

| code | severity | repairScope |
|---|---|---|
| `PLAN_CONTRACT_DIGEST_MISMATCH` | BLOCKER | `CONTRACT` |
| `PLAN_SCHEDULE_MISSING` | BLOCKER | `CONTRACT` |
| `PLAN_SCHEDULE_GAP` | BLOCKER | `CONTRACT` |
| `PLAN_CHAPTER_GAP` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_BEAT_DUPLICATE` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_BEAT_UNASSIGNED` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_BEAT_OUTSIDE_CHAPTER` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_BEAT_DEPENDENCY_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_BEAT_DEPENDENCY_SKIPPED` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_CLUE_PLANT_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_CLUE_PAYOFF_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_CLUE_STRAY` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_REVEAL_BOUNDARY` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_KNOWLEDGE_SOURCE_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_STATE_DEPENDENCY` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_THREAD_OPEN` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_THREAD_PAYOFF_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_CLOSURE_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_MOTIVE_MISSING` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_SCRIPT_TARGET_MISSING` | BLOCKER | `EXPAND_SCRIPTS` |
| `PLAN_SCRIPT_DUPLICATE_EVENT` | FIXABLE | `EXPAND_SCRIPTS` |
| `PLAN_REVISION_STALE` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_SNAPSHOT_STATE_CONFLICT` | BLOCKER | `COMPILE_PLAN` |
| `PLAN_STYLE_SUGGESTION` | ADVISORY | `NONE` |

---

### Task B1: 建立 PlanGraph 记录类型

**Boundary:** 只定义计划图记录和基础不变量，不做编译、分配或持久化。

**Why:** 先固定下游都依赖的稳定类型，避免各模块自行定义章节/拍结构。

**Inputs:** StoryContract、schedule、candidate 和叙事规则所需字段。

**Inputs:** 必须先完成 A6；A6 已向 `PlanContracts` 增加 `StateChangeKind` 与 `ThreadEffectKind`。B1 不得重复添加或修改这两个枚举。

**Outputs:** PlanGraphRecords、CompiledStoryPlan、PlanCompileInput。

**Pass Criteria:** 编号连续、集合不可变、canonical hash 稳定，定向测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanGraphRecords.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/CompiledStoryPlan.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanCompileInput.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanGraphRecordsTest.java`

**Interfaces:**
- Consumes: A6 的 `PlanContracts.StateChangeKind`、`PlanContracts.ThreadEffectKind`
- Produces:

```java
public final class PlanGraphRecords {
    public record BeatCandidate(
            String beatKey, String source, Long assetId, Long arcId,
            Integer arcSegNo, int preferredChapter, String beatType,
            String arcSlot, BigDecimal pacing, List<Integer> dependsOn) {}
    public record PlannedScene(
            int sceneNo, String beatKey, String goal,
            List<String> entryState, List<String> exitState,
            List<String> carryKnowledge, List<String> revealKnowledge) {}
    public record PlannedChapter(
            int chapterNo, int volumeNo, String title, int budget,
            List<PlannedScene> scenes) {}
    public record PlannedBeat(
            int seq, String beatKey, String source, Long assetId, Long arcId,
            Integer arcSegNo, String beatType, BigDecimal pacing,
            int chapterNo, int volumeNo, int sceneNo,
            PlanContracts.StoryLayer storyLayer,
            PlanContracts.BeatRole beatRole,
            PlanContracts.AppearanceMode appearanceMode,
            String goal,
            List<String> focusCharacters, List<String> threadRefs,
            List<KnowledgeEffect> knowledgeEffects,
            List<StateEffect> stateEffects,
            List<Integer> dependsOn, String payoffTarget,
            List<String> requiresKnowledge, List<String> writesKnowledge) {}
    public record KnowledgeEffect(List<String> characterKeys, String fact) {}
    public record StateEffect(String characterKey,
                              PlanContracts.StateChangeKind kind,
                              String detail) {}
    public record ThreadEffect(String threadKey,
                               PlanContracts.ThreadEffectKind effect,
                               String detail) {}
    public record SkippedBeat(String beatKey, String reason) {}
    public record BeatSolveResult(List<PlannedBeat> beats,
                                  List<SkippedBeat> skipped,
                                  List<PlanIssue> issues) {}
    public record StateSnapshot(int chapterNo,
                                Map<String, List<String>> states,
                                Set<String> knowledge) {}
    public record KnowledgeEvent(int chapterNo, String beatKey,
                                 List<String> characterKeys, String fact) {}
    public record ThreadEvent(int chapterNo, String beatKey,
                              String threadKey,
                              PlanContracts.ThreadEffectKind effect) {}
}

public record CompiledStoryPlan(
        long runId,
        int contractVersion,
        String contractHash,
        PlanContracts.ScopeContract scope,
        List<ChapterScheduleEntry> schedule,
        List<PlanContracts.VolumeContract> volumes,
        List<PlanContracts.CharacterContract> characters,
        List<PlanGraphRecords.PlannedChapter> chapters,
        List<PlanGraphRecords.PlannedBeat> beats) {}

public record PlanCompileInput(
        long runId,
        StoryContract contract,
        PlanContracts.ScopeContract scope,
        ScaffoldContract scaffold,
        List<ChapterScheduleEntry> schedule,
        List<PlanGraphRecords.BeatCandidate> candidates,
        BigDecimal defaultPace) {}
```

- [ ] **Step 1: 写失败测试**

```java
void plannedBeatRejectsUnknownBeatRoleAtConstructionContract()
void plannedChapterRequiresContinuousSceneNumbers()
void compiledPlanCanonicalHashIgnoresMapInsertionOrder()
```

最后一条由 `CompiledStoryPlan.canonicalHash()` 提供。

- [ ] **Step 2: 运行失败**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanGraphRecordsTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 records**

所有集合字段在 compact constructor 中 `List.copyOf`；null 集合转空列表；章节和场景编号必须升序连续。

- [ ] **Step 4: 再跑测试**

Expected: PASS。

---

### Task B2: V139 PlanSnapshot 表、DO 与 Mapper

**Boundary:** 只实现 V139、PlanSnapshot DO/Mapper 和 projection 所需列，不实现业务流程。

**Why:** 为计划建立唯一不可变版本源，并提供正文与计划版本绑定的数据库基础。

**Inputs:** V139 DDL、现有 chapters/beat_instance 表结构。

**Outputs:** 新表/列、DO、Mapper、XML 和迁移测试。

**Pass Criteria:** 迁移可在空测试库执行，JSONB 写入正确，唯一键和 active revision 可查询，测试通过。

**Files:**
- Create: `novelgen-data/src/main/resources/db/migration-derive/V139__derive_plan_snapshot.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/DerivePlanSnapshotDO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/ChapterDO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/BeatInstanceDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/DerivePlanSnapshotMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DerivePlanSnapshotMapper.xml`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveChapterMapper.xml`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveBeatInstanceMapper.xml`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanSnapshotMapperTest.java`

**Interfaces:**
- Produces:

```java
interface DerivePlanSnapshotMapper extends BaseMapper<DerivePlanSnapshotDO> {
    long insertFull(DerivePlanSnapshotDO snapshot);
    DerivePlanSnapshotDO findMaxRevision(long runId);
    DerivePlanSnapshotDO findRevision(long runId, int revision);
    int admit(long runId, int revision, String reportJson, String planHash);
    int supersedeOtherAdmitted(long runId, int keepRevision);
    int updateRunActiveRevision(long runId, int revision);
}
```

- [ ] **Step 1: 写 Mapper 行为测试**

断言：

- `insertFull` 返回 id；
- 同 run 同 revision 唯一；
- `admit` 只更新 `DRAFT`；
- `updateRunActiveRevision` 更新 `run.active_plan_revision`；
- `graph`/`admission_report` 走 JSONB。

- [ ] **Step 2: 写 V139 迁移**

DDL 使用主计划第 4.1 节，并新增：

```sql
ALTER TABLE chapters ADD COLUMN plan_revision INTEGER;
ALTER TABLE beat_instance ADD COLUMN plan_revision INTEGER;
```

迁移只新增列/表/索引，不改旧数据。两个 DO 增加 `Integer planRevision`；
所有显式 insert/update/select XML 用 grep 核过后补列。

- [ ] **Step 3: 实现 DO/Mapper/XML**

`insertFull` 使用 `<select resultType="long"> ... RETURNING id`，禁止 `<update>` 获取 RETURNING。

- [ ] **Step 4: 运行测试**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanSnapshotMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B3: PlanSnapshotService

**Boundary:** 只管理 PlanSnapshot 的 revision、DRAFT/ADMITTED/SUPERSEDED 生命周期。

**Why:** 禁止多个计划版本被同时视为有效，保证正文始终绑定一个 ADMITTED revision。

**Inputs:** CompiledStoryPlan、contract hash、PlanSnapshotMapper。

**Outputs:** persistDraft/require/load/admit/supersede 服务接口。

**Pass Criteria:** revision 单调、admit 原子、旧版本 supersede、stale 检测测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanSnapshotService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanSnapshotServiceTest.java`

**Interfaces:**
- Consumes: `DerivePlanSnapshotMapper`
- Produces:

```java
DerivePlanSnapshotDO persistDraft(long runId, CompiledStoryPlan plan);
DerivePlanSnapshotDO requireRevision(long runId, int revision);
DerivePlanSnapshotDO requireActive(long runId);
void admit(long runId, int revision, String reportJson, String planHash);
void supersedeAndActivate(long runId, int revision);
CompiledStoryPlan loadCompiled(long runId, int revision);
```

- [ ] **Step 1: 写测试**

```java
void persistDraftAssignsNextRevision()
void persistDraftUsesContractHashFromPlan()
void admitRejectsNonDraftRevision()
void supersedeKeepsOnlyOneAdmittedRevision()
void requireActiveFailsWhenNoAdmission()
void planRevisionStaleWhenContractHashDiffers()
```

- [ ] **Step 2: 实现 revision 分配**

事务中读取当前最大 revision 并插入 `max+1`；唯一键冲突时重试一次后抛 `PLAN_REVISION_CONFLICT`。

- [ ] **Step 3: 实现 admit 原子更新**

顺序：更新 snapshot 为 ADMITTED → supersede 其他 ADMITTED → 更新 active pointer。三步同事务。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanSnapshotServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B4: BeatAssignmentSolver 固定约束

**Boundary:** 只分配固定结构和强制拍，不处理剩余候选拍。

**Why:** 固定线索、reveal 和 closure 是合同事实，不能被负载均衡或模型偏好移动。

**Inputs:** StoryContract、ScaffoldContract、schedule、BeatCandidate、defaultPace。

**Outputs:** BeatSolveResult 的固定拍部分、skipped、PlanIssue。

**Pass Criteria:** plant/payoff/reveal/closure 章号完全正确，所有固定拍有合法 beatType/source/role。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/BeatRoleClassifier.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/BeatAssignmentSolver.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/BeatRoleClassifierTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/BeatAssignmentSolverLockTest.java`

**Interfaces:**
- Produces:

```java
PlanGraphRecords.BeatSolveResult solve(
        StoryContract contract,
        PlanContracts.ScopeContract scope,
        ScaffoldContract scaffold,
        List<ChapterScheduleEntry> schedule,
        List<PlanGraphRecords.BeatCandidate> candidates);
```

- [ ] **Step 1: 写固定约束测试**

```java
void cluePlantBeatIsAssignedToPlantChapter()
void cluePayoffBeatIsAssignedToPayoffChapter()
void finalRevealIsAssignedToRevealChapter()
void finalClosureIsAssignedToLastChapter()
void forbiddenDeathAndSupernaturalCandidatesAreSkipped()
```

- [ ] **Step 2: 实现锁定顺序**

1. 解析合同 clue 到 beatKey；
2. 锁 plant/payoff；
3. 锁 FINAL_REVEAL；
4. 锁 FINAL_CLOSURE；
5. 记录 skipped reason。

锁定规则是确定的：

- 对每条 `clue`，无论输入材料是否已有相似拍，都生成两个合成拍：
  `SYN-CLUE-PLANT-<clueKey>` 和 `SYN-CLUE-PAYOFF-<clueKey>`；
- plant/payoff 的 chapter 直接取合同，禁止从候选拍偏好章推断；
- reveal 章生成 `SYN-FINAL-REVEAL`；
- 最后一章生成 `SYN-FINAL-CLOSURE-<chapterNo>`；
- 现有候选拍不能抢占上述固定位置；
- 若合同 clue 在 schedule 中缺失，返回 `PLAN_CLUE_PLANT_MISSING` 或 `PLAN_CLUE_PAYOFF_MISSING`，不创建猜测拍。

候选 `beatKey`：

- 如果输入已有稳定 `beatKey`，原样使用；
- 否则使用 `"B" + %02d` 格式的临时 seq；
- 同一 run 内必须唯一；重复返回 `PLAN_BEAT_DUPLICATE`。

合成拍字段固定：

```text
source=SYNTHETIC
assetId=null
arcId=null
arcSegNo=null
pacing=input.defaultPace
appearanceMode=LIVE
focusCharacters=[clue.ownerKey] 或 [C1]
threadRefs=[clueKey]
```

`beatType` 由 role 确定性映射：

```text
CLUE_PLANT / CASE_SETUP / HOOK -> 铺垫
CLUE_PAYOFF / FINAL_REVEAL / FALSE_ANSWER -> 反转
CHARACTER_CHOICE -> 抉择
LOCAL_CLOSURE / FINAL_CLOSURE -> 收束
其他 -> 事件
```

所有拍分配完成后，按 `chapterNo, sceneNo, beatKey` 排序，再从 1 连续赋
`seq`。`seq` 是 `beat_instance.run_id,seq` 唯一键的一部分，不得由模型提供。

`BeatRoleClassifier` 固定映射：

| 输入 beatType | role |
|---|---|
| 事件 | MAIN_ADVANCE |
| 反转 | FALSE_ANSWER |
| 情绪 | HIGHLIGHT |
| 抉择 | CHARACTER_CHOICE |
| 收束 | LOCAL_CLOSURE |
| 铺垫 | CASE_SETUP |

未命中时按弧位：开头→CASE_SETUP，中段→MAIN_ADVANCE，结尾→LOCAL_CLOSURE。
不得接受模型提供的新 role。

- [ ] **Step 3: 确认不分配结构**

本任务只处理固定拍；未固定拍保留在 `pending`，下一任务处理。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=BeatRoleClassifierTest,BeatAssignmentSolverLockTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B5: BeatAssignmentSolver 剩余拍与负载

**Boundary:** 只为未固定候选拍选择章节，并完成 seq/dependency 归一。

**Why:** 消除模型对章节号和拍顺序的自由决定，同时保持确定性和可重复性。

**Inputs:** Task B4 的 pending 候选、依赖、章负载和红线规则。

**Outputs:** 完整 assigned/skipped beats、最终 seq、重写后的 dependsOn。

**Pass Criteria:** 同一输入结果相同，依赖不倒序，拍不跨 reveal 非法边界，所有拍恰好一次。

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/BeatAssignmentSolver.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/BeatAssignmentSolverCostTest.java`

**Interfaces:**
- Consumes: Task B4 的 `pending`
- Produces: 所有输入 beat 恰好一次 assigned/skipped

- [ ] **Step 1: 写测试**

```java
void preferredChapterWinsWhenCapacityAvailable()
void dependencyOrderIsNeverReversed()
void identicalInputProducesIdenticalOrdering()
void overloadedChapterMovesOnlyMovableBeat()
void everyBeatIsAssignedOrSkippedExactlyOnce()
void noLowQualityRelocationCrossesRevealBoundary()
```

- [ ] **Step 2: 实现成本函数**

评分优先级固定：

1. 不违反依赖和 reveal 边界；
2. 优先 preferred chapter；
3. 优先 arcSlot 匹配；
4. 优先章负载接近目标；
5. beat key 升序作为稳定 tie-breaker。

不得引入随机数。

实现使用整数成本，不使用浮点比较：

```text
cost =
  revealBoundaryPenalty * 100000 +
  dependencyPenalty      * 10000 +
  preferredDistance      * 100 +
  roleMismatchPenalty    * 10 +
  chapterLoadPenalty
```

每次选择 `cost` 最小的 chapter；同 cost 按 chapterNo 升序。
候选按 beatKey 升序处理，保证同一输入得到同一结果。

最终序号规则：

1. 所有 assigned beat 按 `chapterNo, sceneNo, beatKey` 排序；
2. 从 1 连续赋最终 `seq`；
3. 维护 `candidateSeq -> finalSeq` 映射；
4. 把每个 `dependsOn` 中的 candidate seq 重写为 final seq；
5. 依赖指向 skipped beat → `PLAN_BEAT_DEPENDENCY_SKIPPED`；
6. 依赖指向不存在 candidate → `PLAN_BEAT_DEPENDENCY_MISSING`。

最终 `beatKey` 始终稳定，不能被 seq 重排影响。

红线跳过只允许使用当前 `conflictSkipReason` 已定义的领域词：

- 无死亡：尸体/遗骸/殡葬/死亡/死者/命案/凶杀；
- 无超自然：诡异/人形/化形/超自然/怪谈/灵异/鬼/异象。

命中后写 `SkippedBeat(beatKey, reason)`，不得悄悄丢弃。

- [ ] **Step 3: 实现兜底**

若无法分配且没有任何 skipped 理由，返回 `PLAN_BEAT_UNASSIGNED`，不伪造拍。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=BeatAssignmentSolverCostTest,BeatAssignmentSolverLockTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B6: ContinuitySimulator

**Boundary:** 只模拟计划和知识/状态，不生成叙事、不写数据库。

**Why:** 在写正文前发现知识来源、线索时序、角色状态和线程闭合硬伤。

**Inputs:** Compiled plan 的 beats、合同 clue/thread/cast。

**Outputs:** 逐章 StateSnapshot、KnowledgeEvent、ThreadEvent 和 PlanIssue。

**Pass Criteria:** 所有计划硬不变量可被检出，模拟结果可重复，定向测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/ContinuitySimulator.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/ContinuitySimulatorTest.java`

**Interfaces:**
- Produces:

```java
SimulationResult simulate(StoryContract contract,
                          List<PlanGraphRecords.PlannedBeat> beats);

record SimulationResult(
        List<PlanGraphRecords.StateSnapshot> chapterStates,
        List<PlanGraphRecords.KnowledgeEvent> knowledgeEvents,
        List<PlanGraphRecords.ThreadEvent> threadEvents,
        List<PlanIssue> issues) {}
```

- [ ] **Step 1: 写知识测试**

```java
void characterCannotRequireKnowledgeBeforeSourceBeat()
void sourceBeatWritesKnowledgeOnlyForFocusCharacters()
void sharedKnowledgeCanBeCarriedBySameSceneFocusGroup()
void laterChapterKnowledgeCannotAppearEarlier()
```

- [ ] **Step 2: 写线索/状态测试**

```java
void clueCannotEchoBeforePlant()
void clueCannotPayoffBeforePlant()
void clueCannotPayoffTwice()
void departedCharacterCannotChangeLocationLater()
void deadCharacterCanAppearOnlyInContractAllowedMode()
```

- [ ] **Step 3: 实现按章 replay**

输入按 `chapterNo, sceneNo, beatKey` 排序；每章复制上一章状态后应用 effects，不允许原地修改历史 snapshot。

精确规则：

- 初始 knowledge 为空；合同 cast 不自动知道任何事实；
- `KnowledgeEffect.characterKeys` 必须是该 beat 的 focusCharacters 子集；
- beat 的任一 focusCharacter 已知某 required fact，即允许该场景共享；
- beat 写出 fact 后，只有该 beat focusCharacters 获得 knowledge；
- `StateChangeKind.DEAD` 一旦出现，后续 LIVE 拍返回 `PLAN_STATE_DEPENDENCY`；
- `LOCATION` 覆盖该角色 location；`PRESENT/ABSENT` 与 location 分开保存；
- `POSSESSION_GAIN/LOSS` 用有序字符串列表维护；
- clue 的 PLANT/PAYOFF 由 schedule entry 驱动，不信任模型自由文本；
- `ThreadEffectKind.OPEN` 只能首次一次；`ECHO` 只能 open 后；`PAYOFF/CLOSE` 只能一次。

模拟器发现的全部问题追加到 `List<PlanIssue>`，不抛异常中断整批检查。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=ContinuitySimulatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B7: StoryPlanCompiler 结构编译

**Boundary:** 只构造结构计划和章节/场景骨架，不调用 LLM。

**Why:** 先确保章节、拍、场景和依赖可执行，再由叙事层补文字，分离结构与内容。

**Inputs:** PlanCompileInput、B4/B5/B6 结果。

**Outputs:** CompiledStoryPlan 结构版、空 goal 的脚本和 hardIssues。

**Pass Criteria:** 12/15 章均完整、每章有场景、每场景恰好一个 beatKey，hash 稳定。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/StoryPlanCompiler.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanCompileResult.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/StoryPlanCompilerTest.java`

**Interfaces:**
- Consumes: Tasks B1、B4、B5、B6
- Produces:

```java
PlanCompileResult compile(PlanCompileInput input);

record PlanCompileResult(
        CompiledStoryPlan plan,
        List<PlanIssue> hardIssues,
        List<String> warnings) {}
```

- [ ] **Step 1: 写结构 compiler 测试**

使用 12 章与 15 章 fixture：

```java
void compilerCreatesOneChapterPerScopeChapter()
void compilerCreatesAtLeastOneScenePerChapter()
void compilerUsesOnlyContractCharactersThreadsAndClues()
void compilerCarriesScheduleDigest()
void compilerReturnsContinuityIssuesNotExceptions()
void sameInputProducesSameCanonicalPlanHash()
```

- [ ] **Step 2: 增加 candidate adapter**

在 `DerivePlanService` 增加 public/package bridge：

```java
List<PlanGraphRecords.BeatCandidate> candidatesForCompiler(long runId);
```

该 adapter 只转换数据，不修改 run。

- [ ] **Step 3: 实现 compiler**

顺序：

1. 用 scope 和 schedule 创建全部章节；
2. 调 solver，得到 beats/skipped/issues；
3. 按 `chapterNo asc, beatKey asc` 将 beat 分组；
4. 每章每个 beat 生成一个 `PlannedScene`，`sceneNo` 从 1 连续且 `beatKey` 唯一；
5. 无 beat 的章生成 `SYN-PLAN-FILLER-<chapterNo>`，role=`MAIN_ADVANCE`，并记录 `PLAN_SCRIPT_TARGET_MISSING`；
6. 调 continuity simulation；
7. 合并 solver 与 simulation issues；
8. 返回 `CompiledStoryPlan + hardIssues + warnings`。

本任务不调用 LLM，goal 先使用 deterministic placeholder：

```text
推进第N章既定目标：<beatRole>
```

有 hardIssues 仍返回已构造 plan，供 auditor 定位；无法构造章节骨架时才抛
`PlanCompilerException`。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=StoryPlanCompilerTest,BeatAssignmentSolverCostTest,ContinuitySimulatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B8: 计划叙事扩写

**Boundary:** 只把强类型叙事字段合并进结构计划，不改变结构身份。

**Why:** 让模型承担 goal/effects 文本，同时禁止它改章号、角色、线程或 beat 分配。

**Inputs:** 结构 CompiledStoryPlan、RouteNarrative 输出。

**Outputs:** 带真实 goal/effects 的 CompiledStoryPlan 或结构化错误。

**Pass Criteria:** 每个 beatKey 只被填充一次，未知/缺失/跳过固定拍被拒绝，测试通过。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanNarrativeExpander.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/StoryPlanCompiler.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanNarrativeExpanderTest.java`

**Interfaces:**
- Consumes: 结构 `CompiledStoryPlan`、`RouteNarrative` schema
- Produces:

```java
CompiledStoryPlan expand(CompiledStoryPlan structural,
                         RouteNarrative narrative);
```

- [ ] **Step 1: 写叙事合并测试**

```java
void beatGoalIsMergedOnlyByExistingBeatKey()
void unknownBeatKeyIsRejected()
void missingBeatGoalFailsBeforeAdmission()
void skippedBeatMustHaveNonblankReason()
void narrativeCannotChangeChapterOrBeatRole()
```

- [ ] **Step 2: 实现 writer flow**

每个卷一次 `LlmJson.askStructured`；prompt 只提供该卷 skeletal beats 和允许 key，要求返回 `beatNarratives/skipped`。

每个 prompt 必须携带：

- `runId`
- `planRevision`（结构编译阶段先为 0，持久化时替换）
- 当前卷 chapter/beat key 清单
- cast keys
- contract thread keys
- clue key 与 plant/payoff 章
- 该卷所有 fixed beats

输出中的每个 beatKey 必须来自该清单。

- [ ] **Step 3: 合并规则**

- 不新增 beat；
- 不删除 scheduled beat；
- 只填 goal/effects；
- effects key 必须在 cast/threads 中；
- skipped beat 必须带 reason。

不得跳过以下 synthetic beat：

- `SYN-CLUE-PLANT-*`
- `SYN-CLUE-PAYOFF-*`
- `SYN-FINAL-REVEAL`
- `SYN-FINAL-CLOSURE-*`

若模型 skipped 其中任一，返回 `PLAN_CLOSURE_MISSING`。
允许 skipped 的候选 beat 必须落在 `skipped` 列表，不能消失。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanNarrativeExpanderTest,RouteNarrativeContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B9: PlanAuditor

**Boundary:** 只读评估计划并产生结构化审计报告，不修改计划。

**Why:** 把以前依赖 P6 发现的结构矛盾前移成确定性审计，并给出可修复路径。

**Inputs:** CompiledStoryPlan、StoryContract、ContinuitySimulator 结果。

**Outputs:** PlanAuditReport、稳定 code/severity/path/repairScope。

**Pass Criteria:** run59 五类问题全部 BLOCKER 定位到具体章/拍，纯 advisory 不阻断。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/PlanAuditIssue.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/PlanAuditReport.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/PlanAuditor.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanAuditorTest.java`

**Interfaces:**
- Produces:

```java
PlanAuditReport audit(CompiledStoryPlan plan, StoryContract contract);

record PlanAuditIssue(
        String code, Severity severity, String path,
        Integer chapterNo, String beatKey,
        RepairScope repairScope, String message,
        String expected, String actual) {}

enum Severity { BLOCKER, FIXABLE, ADVISORY }
record PlanAuditReport(List<PlanAuditIssue> issues) {
    boolean admitted();
}
```

- [ ] **Step 1: 写合同/日程审计测试**

```java
void missingCluePlantIsBlocker()
void revealWithoutPayoffIsBlocker()
void duplicateBeatAssignmentIsBlocker()
void missingMotiveTagAtRevealIsBlocker()
void beatOutsideScheduleIsBlocker()
void advisoryStyleIssueDoesNotBlockAdmission()
```

- [ ] **Step 2: 写 run59 regression**

构造 run59 的 5 类问题，断言 path 分别指向 chapter 11–15 的对应 beat/script，不只返回中文长文本。

- [ ] **Step 3: 实现 auditor**

依次调用：合同结构验证、schedule 覆盖、beat 唯一性、continuity result、reveal closure、chapter script completeness、thread/clue closure。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanAuditorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B10: PlanProjectionService

**Boundary:** 只把已编译计划投影到现有 chapters/beat_instance，不执行 admission 决策。

**Why:** 让正文层继续使用既有表和查询，同时通过 plan_revision 防止跨版本污染。

**Inputs:** CompiledStoryPlan、novelId、runId、revision、现有 mapper。

**Outputs:** 带 plan_revision 的章节和拍投影、同步后的 novel chapter_count。

**Pass Criteria:** 禁止覆盖已有正文；投影行数与计划完全一致；其他 novel 不受影响。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/PlanProjectionService.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/ChapterMapper.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/BeatInstanceMapper.java`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveChapterMapper.xml`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveBeatInstanceMapper.xml`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanProjectionServiceTest.java`

**Interfaces:**
- Consumes: `CompiledStoryPlan`、`ChapterMapper`、`BeatInstanceMapper`
- Produces:

```java
void activate(long runId, long novelId, int revision, CompiledStoryPlan plan);
```

本任务同时给 mapper 增加：

```java
int ChapterMapper.deleteByNovelId(long novelId);
long ChapterMapper.insertPlanChapter(ChapterDO chapter);
int ChapterMapper.updateContentIfRevision(long id, int planRevision,
                                           String content, int charCount,
                                           String comment, String status);

int BeatInstanceMapper.deleteByRun(long runId);
int BeatInstanceMapper.updateFilledIfRevision(long runId, String beatKey,
                                               int planRevision,
                                               String filledText, String status);
```

返回值必须是实际影响行数；正文写入的条件不满足时由调用方报错。

- [ ] **Step 1: 先核真实表结构**

Run:

```bash
docker exec pg-vector psql -U postgres -d novel_gen -c '\d chapters'
# 若执行环境使用 derive_gen，则使用对应的 derive 表结构命令
docker exec pg-vector psql -U postgres -d derive_gen -c '\d beat_instance'
```

Expected: 记录实际必填列和唯一键；不得凭记忆写列名。

- [ ] **Step 2: 写失败测试**

```java
void activateRejectsRunWithExistingChapterText()
void activateDeletesOldBeatInstancesForRun()
void activateWritesOneChapterPerPlannedChapter()
void activateWritesEveryPlannedBeatWithPlanRevision()
void activateDoesNotTouchAnotherNovel()
```

- [ ] **Step 3: 实现投影**

事务顺序：

1. 验证该书所有章 `content` 为空；
2. 删除该 run 旧 `beat_instance`；
3. 删除/重建该 novel 的空章行（不得影响其他 novel）；
4. 写章节行，`plan_revision=revision`；
5. 用现有 `BeatInstanceMapper.insertFull` 写全部 beat，`plan_revision=revision`；
6. 更新 novel 的 `chapter_count`。

任一步受影响行数不符合预期即回滚。

写 `beat_instance` 时逐字段映射：

```text
seq, run_id, source, asset_id, arc_id, arc_seg_no, beat_type, pacing,
chapter_no, volume_no, filled_text(status PLANNED 时为空), status='PLANNED',
beat_key, chapter_seq=sceneNo, pack_version=revision,
story_layer, beat_role, route_contract=PlannedBeat JSONB, plan_revision
```

`route_contract` 必须包含 focusCharacters/threadRefs/effects/dependsOn/payoffTarget/
requiresKnowledge/writesKnowledge，使用 `::jsonb`。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanProjectionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B11: PlanAdmissionService 与 P6 适配

**Boundary:** 只做 admission 决策、持久化和 P6 适配，不改计划内容。

**Why:** 把“能否开始正文”收敛为确定性结果，并让旧 /gate API 保持兼容。

**Inputs:** PlanAuditor report、PlanSnapshotService、PlanProjectionService。

**Outputs:** PlanAdmissionResult、ADMITTED snapshot、active revision、P6 GO/REWORK 映射。

**Pass Criteria:** BLOCKER 不得准入，advisory 可准入，projection 失败整体回滚。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/PlanAdmissionService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/audit/SemanticPlanReviewService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanAdmissionServiceTest.java`
- Modify: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateContractTest.java`

**Interfaces:**
- Consumes: `PlanAuditor`、`PlanSnapshotService`、`PlanProjectionService`
- Produces:

```java
PlanAdmissionResult admit(long runId, int revision);

enum AdmissionDecision { ADMIT, REPAIR, REJECT }
record PlanAdmissionResult(long runId, int revision,
                           AdmissionDecision decision,
                           PlanAuditReport report) {}

interface SemanticPlanReviewService {
    List<PlanAuditIssue> review(CompiledStoryPlan plan);
}
```

- [ ] **Step 1: 写 admission 测试**

```java
void blockerReturnsRepairAndDoesNotAdmit()
void advisoryOnlyReturnsAdmit()
void admitPersistsReportAndUpdatesActiveRevision()
void secondAdmitSupersedesFirst()
void admitActivatesProjectionBeforeReturning()
void projectionFailureLeavesNoAdmittedPlan()
void semanticReviewCannotChangeContractOrSchedule()
void semanticReviewIssueBecomesAdvisoryOnly()
```

- [ ] **Step 2: 实现 admission**

准入决策只由 `PlanAuditor` 的确定性结果决定：

- 存在 BLOCKER → `REPAIR`；
- 只有 ADVISORY → `ADMIT`。

`SemanticPlanReviewService` 在 deterministic audit 之后运行，其全部结果强制转换为
`ADVISORY`，并追加到 report。它不得把 ADMIT 改成 REPAIR，也不得修改 contract/plan。
原因：主观模型审稿不能成为“全书正文是否可开始”的硬熔断。
调用 `PlanSnapshotService.admit(...)` 前，用唯一注入的 `ObjectMapper` 将 `PlanAuditReport` 序列化为 `admissionReportJson`；不得手拼 JSON。

提交顺序必须为：

1. `PlanProjectionService.activate(...)`；
2. `PlanSnapshotService.admit(...)`；
3. snapshot 标 `ADMITTED`；
4. 更新 `run.active_plan_revision`；
5. 返回 `PlanAdmissionResult`。

整个序列同事务；projection 或 admit 失败不得留下 ADMITTED 状态。

- [ ] **Step 3: 适配 `/gate`**

`ADMIT -> GO/PASS`，`REPAIR -> REWORK`，`REJECT -> FAILED`；禁止把自由模型 directive 回写合同/日程。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=PlanAdmissionServiceTest,PlanAuditorTest,DeriveGateContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

### Task B12: Legacy adapter 与引擎切换

**Boundary:** 只支持旧 run 的只读适配和引擎选择，不给旧 run 自动造新事实。

**Why:** 避免重构破坏历史数据，同时让新 run 明确进入 compiler-v1 路径。

**Inputs:** 旧 chapters/beat_instance/step_status 和 engine 配置。

**Outputs:** LegacyPlanSnapshotAdapter 和受控配置项。

**Pass Criteria:** 有正文旧 run 不迁移；缺失计划返回明确错误；非法 engine 启动失败。

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/LegacyPlanSnapshotAdapter.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/compiler/DerivePlanEngineProperties.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveResetService.java`
- Modify: `novelgen-derive-app/src/main/resources/application.yaml`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/LegacyPlanSnapshotAdapterTest.java`

**Interfaces:**
- Produces:

```java
CompiledStoryPlan loadLegacy(long runId);
```

- [ ] **Step 1: 写兼容测试**

```java
void legacyRunWithoutSnapshotCanBeRead()
void legacyRunWith正文IsNotAutoMigrated()
void scaleResetDeletesLegacySnapshot()
void spineResetCreatesNewPlanRevision()
```

- [ ] **Step 2: 实现 adapter**

只读旧表；若章/拍编号不完整，返回 `LEGACY_PLAN_INCOMPLETE`，不猜造拍。

- [ ] **Step 3: 增加开关**

```yaml
novelgen:
  derive:
    engine: compiler-v1
```

新 run 默认 compiler-v1；legacy 仅允许读取/排障。

`DerivePlanEngineProperties` 绑定 `novelgen.derive.engine`，只允许 `compiler-v1|legacy`；
非法值启动失败。不得静默回退 legacy。

- [ ] **Step 4: 验证**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=LegacyPlanSnapshotAdapterTest,DeriveResetServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS。

---

## Completion Check

- [ ] 新 run 生成唯一 DRAFT snapshot，再提升为唯一 ADMITTED snapshot。
- [ ] active revision 和 contract hash 一致。
- [ ] solver 不移动固定 clue/reveal/closure 拍。
- [ ] continuity 全章模拟可重复。
- [ ] run59 五类问题在写正文前被审计。
- [ ] `/gate` 兼容旧调用，但不再用 LLM 改写结构。
- [ ] 全量测试通过。
