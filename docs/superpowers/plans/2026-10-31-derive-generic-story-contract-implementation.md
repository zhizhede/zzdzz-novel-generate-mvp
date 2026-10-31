# 随机衍生通用故事合同 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前悬疑专用故事合同抽象为“通用故事核心 + 类型插件 + 可执行叙事义务”，让 ⑥b 具体稿在 P6 之前就能被确定性校验和局部回炉。

**Architecture:** 保留现有 `SAMPLING → PLANNING → DRAFTING → P6 → ⑦ → ⑧` 主链；新增 `story`/`genre` 领域层，将 `StoryContract` 适配为 `MysteryProfile`，把合同、日程、卷合同、角色合同投影为通用 `StoryObligation`；在 `DeriveDraftService` 的每个 BATCH 窗口后执行义务校验，并把可修复错误转成当前窗回炉。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Flyway、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-10-31-derive-generic-story-contract-design.md`

## Global Constraints

- 严格遵守 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`。
- 代码只在 `novelgen-service`、`novelgen-model`、`novelgen-data`、`novelgen-llm` 模块内按既有依赖方向修改。
- SQL 只能放在 `novelgen-data/src/main/resources/db/migration-derive/` 和 mapper XML。
- 不删除旧 `StoryContract`、`ChapterScheduleEntry`、`DeriveGateService`、`DeriveQcService`；旧路径必须保持可读。
- 新通用核心不得出现 `culprit`、`motive`、`method`、`revealChapter` 等悬疑专属字段。
- 模型不得输出新的 cast、线程 key、线索 key、揭示章或结局事实。
- 任何数据库迁移必须使用新的 Flyway 版本，不得修改历史迁移。
- 未经用户明确授权，禁止 commit、push、部署。
- 每个任务先写失败测试，再实现，再运行相关测试；全任务完成后运行全量测试。
- 提交步骤只有在用户明确授权后执行；未授权时保留工作区并记录未提交状态。

## Review Focus

以下输入和失败模式最可能导致实现错误，必须由对应任务的测试覆盖：

1. **新旧 ROUTE 契约错配**：提示词、schema、Java parser 必须使用同一字段集合；`ContractPromptArityTest` 和 `RoutePromptContractTest` 必须阻止 `beatNarratives` 混入旧 ROUTE。
2. **悬疑字段泄漏进通用核心**：`StoryKernel`、`StoryObligation`、通用 validator 的单测必须拒绝 `culprit`、`motive`、`method`、`revealChapter`。
3. **义务投影重复或遗漏**：同一线索同一章出现两次 plant/payoff，或必需 setup 没有生成对应义务，必须由投影单测失败。
4. **⑥b 窗口校验假阴性/假阳性**：提前 payoff、重复 payoff、缺 setup、状态冲突、知识来源缺失必须被拒绝；合法的回忆、转述、非 LIVE 出场不得误杀。
5. **旧 run 兼容回归**：旧 `StoryContract`、旧章节日程、旧 P6/QC 读路径必须保持可读；新 run 才要求 `genre` 和 kernel 版本。

---

## Task 1: 新增通用故事核心记录类型

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryKernel.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryEntity.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryRelation.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryState.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryThread.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryEvent.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryObligation.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryConstraint.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryClosure.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryKernelTest.java`

**Interfaces:**

- Consumes: 无；本任务是纯模型层。
- Produces: `StoryKernel` 及其内部 record；后续任务只读这些类型，不修改其字段语义。

**Implementation:**

- [ ] **Step 1: 写失败测试**

在 `StoryKernelTest` 中定义以下测试：

```text
kernelRejectsMysterySpecificFields
entityKeysAreUnique
obligationChapterMustBePositive
obligationKindIsRequired
constraintRangeIsValid
closureRequiresTargetAndChapter
```

测试断言：

- `StoryKernel` 不提供 `culprit`、`motive`、`method`、`revealChapter` 字段；
- `StoryObligation` 的 `chapterNo > 0`；
- `StoryObligation.kind` 非空；
- `StoryConstraint.chapterFrom <= chapterTo`；
- `StoryClosure.targetKey` 和 `requiredChapter` 必填。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryKernelTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 编译失败或测试失败，且失败原因是类型尚不存在。

- [ ] **Step 3: 实现记录类型**

创建以下强类型 record，所有集合在 compact constructor 中做 null-safe copy：

```text
StoryKernel(long runId, int version, String status, ScopeContract scope,
            List<StoryEntity> entities, List<StoryRelation> relations,
            List<StoryState> states, List<StoryThread> threads,
            List<StoryEvent> events, List<StoryObligation> obligations,
            List<StoryConstraint> constraints, List<StoryClosure> closure)

StoryEntity(String key, String kind, String name,
            Map<String,Object> attributes, Integer introducedChapter)

StoryRelation(String key, String leftKey, String rightKey, String kind,
              Integer openedChapter, List<Integer> changedChapters,
              Integer closedChapter, String status)

StoryState(String entityKey, String kind, String value,
           int effectiveChapter, String sourceEventKey)

StoryThread(String threadKey, String kind, String title,
            Integer openedChapter, String status, String ownerKey,
            Integer targetChapter, Map<String,Object> contract)

StoryEvent(String eventKey, int chapterNo, String kind,
           List<String> actorKeys, List<String> targetKeys,
           String goal, String result,
           List<Map<String,Object>> knowledgeEffects,
           List<Map<String,Object>> stateEffects,
           List<Map<String,Object>> threadEffects, String source)

StoryObligation(String obligationKey, int chapterNo, String kind,
                String targetKey, boolean required, boolean forbidden,
                String severity, String sourceContract,
                Map<String,Object> payload)

StoryConstraint(String constraintKey, String scope, Integer chapterFrom,
                Integer chapterTo, String kind, String targetKey, String message)

StoryClosure(String closureKey, String kind, String targetKey,
             int requiredChapter, boolean required,
             List<String> proofEventKeys)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryKernelTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

如用户已授权 commit：`git add` 上述文件并使用 `feat(derive): add generic story kernel records`。未授权则不提交。

---

## Task 2: 新增 GenreProfile 接口、注册表和 MysteryProfile

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreProfile.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreProfileRegistry.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryProfile.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/GenreProfileRegistryTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/MysteryProfileTest.java`

**Interfaces:**

- Consumes: `StoryContract`、`ScopeContract`、`ChapterScheduleEntry`、Task 1 的 `StoryKernel`/`StoryObligation`。
- Produces:

```text
GenreProfileRegistry.require(String genre) -> GenreProfile
MysteryProfile.genre() -> "MYSTERY"
MysteryProfile.parseContract(JsonNode root, ScopeContract scope) -> StoryContract
MysteryProfile.projectObligations(StoryContract contract,
                                  List<ChapterScheduleEntry> schedule) -> List<StoryObligation>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

`GenreProfileRegistryTest`：

```text
registryKnowsMystery
registryRejectsUnknownGenre
registryReturnsSingleMysteryInstance
```

`MysteryProfileTest`：

```text
mysteryContractParsesTruthAndClues
mysteryProjectionCreatesPlantAndPayoffObligations
mysteryProjectionRejectsPayoffBeforePlant
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreProfileRegistryTest,MysteryProfileTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 类型不存在导致失败。

- [ ] **Step 3: 实现接口和注册表**

`GenreProfile` 使用接口方法：

```text
String genre()
StoryContract parseContract(JsonNode root, ScopeContract scope)
List<StoryObligation> projectObligations(StoryContract contract,
                                         List<ChapterScheduleEntry> schedule)
List<PlanContracts.PlanIssue> validateContract(StoryContract contract)
```

`GenreProfileRegistry` 使用 `Map<String,GenreProfile>`；注册 `MYSTERY`。未知类型抛 `IllegalArgumentException("Unknown genre: " + genre)`。

- [ ] **Step 4: 实现 MysteryProfile**

`MysteryProfile.parseContract` 复用 `StoryContractParser.parse` 和 `StoryContractValidator.validateForFreeze`。

`projectObligation` 规则：

- `clue.plantedChapter` → `kind="PLANT_THREAD"`，`targetKey=clueKey`；
- `clue.payoffChapter` → `kind="PAYOFF_THREAD"`，`targetKey=clueKey`；
- `truth.revealChapter` → `kind="FINAL_CLOSURE"`，`targetKey="TRUTH"`；
- `forbidden` → `StoryConstraint` 不进入 obligation 列表。

本任务只负责悬疑插件自己的线索/真相义务；角色、卷和通用日程义务由 Task 3 的 `StoryObligationProjector` 统一补充，并调用本方法。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreProfileRegistryTest,MysteryProfileTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 3: 新增通用义务投影器

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/StoryObligationProjector.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/StoryObligationProjectorTest.java`

**Interfaces:**

- Consumes: `GenreProfile`、`StoryContract`、`ChapterScheduleEntry`、`VolumeContract`、`CharacterContract`。
- Produces:

```text
StoryObligationProjector.project(
    GenreProfile profile,
    StoryContract contract,
    List<ChapterScheduleEntry> schedule,
    List<PlanContracts.VolumeContract> volumes,
    List<PlanContracts.CharacterContract> characters
) -> List<StoryObligation>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
projectorCreatesCluePlantAndPayoff
projectorCreatesCharacterChoiceAndCost
projectorCreatesLocalAndFinalClosure
projectorRejectsDuplicateObligationKey
projectorRejectsObligationOutsideSchedule
projectorDoesNotCreateMysterySpecificFields
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryObligationProjectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现投影器**

`obligationKey` 固定格式：

```text
CH{chapterNo}:{kind}:{targetKey}
```

通用投影来源：

- `CharacterContract.requiredSetup` → `INTRODUCE_ENTITY`
- `CharacterContract.highlightChoice` → `CHARACTER_CHOICE`
- `CharacterContract.highlightCost` → `CHARACTER_COST`
- `VolumeContract.localClosureProof` → `LOCAL_CLOSURE`
- `VolumeContract.globalRole=FINAL` → `FINAL_CLOSURE`

类型插件义务来源：

- 调用 `profile.projectObligations(contract, schedule)` 获取 `PLANT_THREAD`、`PAYOFF_THREAD`、类型专属 closure 等义务；
- 将插件义务与通用义务合并；
- 按 `obligationKey` 去重；
- 任何重复 key 抛 `IllegalArgumentException`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=StoryObligationProjectorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获授权不 commit。

---

## Task 4: 给 run 增加类型元数据并完成 Flyway 迁移

**Files:**

- Create: `novelgen-data/src/main/resources/db/migration-derive/V143__derive_genre_metadata.sql`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/RunDO.java`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveRunMapper.xml`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/dto/derive/RunCreateDTO.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveSampleService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/RunGenreMetadataTest.java`

**Interfaces:**

- Consumes: `RunCreateDTO`、`RunDO`、`DeriveSampleService.create`。
- Produces:

```text
RunDO.genre
RunDO.storyKernelVersion
RunDO.genreContractVersion
RunCreateDTO.genre
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
createRunDefaultsToMystery
createRunAcceptsExplicitGenre
unknownGenreIsRejected
runMetadataIsPersisted
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=RunGenreMetadataTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现 Flyway V143**

`V143__derive_genre_metadata.sql`：

```sql
ALTER TABLE run ADD COLUMN genre VARCHAR(32) NOT NULL DEFAULT 'MYSTERY';
ALTER TABLE run ADD COLUMN story_kernel_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE run ADD COLUMN genre_contract_version INTEGER NOT NULL DEFAULT 1;
```

- [ ] **Step 4: 修改 DO、DTO、mapper、采样服务**

`RunDO` 增加：

```text
private String genre;
private Integer storyKernelVersion;
private Integer genreContractVersion;
```

`RunCreateDTO` 增加 `String genre`。

`DeriveSampleService.create`：

- `genre == null` → `MYSTERY`；
- 允许值由 `GenreProfileRegistry` 决定；
- 未知值抛 `BizException(ErrorCode.PARAM_ERROR, "Unknown genre: ...")`；
- 新 run 默认 `storyKernelVersion=1`、`genreContractVersion=1`。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=RunGenreMetadataTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 5: 将 CONTRACT 接入 GenreProfile

**Files:**

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Modify: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/PromptCatalog.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/GenreContractServiceTest.java`

**Interfaces:**

- Consumes: `GenreProfileRegistry`、`MysteryProfile`。
- Produces:

```text
DeriveContractService.generateIfAbsent(RunDO run,
                                       ScopeContract scope,
                                       ScaffoldContract scaffold) -> StoryContract
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
contractUsesGenreProfileParser
contractUsesGenreProfileValidator
legacyMysteryContractStillParses
unknownGenreFailsBeforeLlmCall
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreContractServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 修改 CONTRACT 流程**

`DeriveContractService.generateIfAbsent`：

```text
String genre = normalizeGenre(run.getGenre());
GenreProfile profile = registry.require(genre);
StoryContract parsed = profile.parseContract(root, scope);
List<String> errors = profile.validateContract(parsed);
```

提示词仍先使用现有 `StoryPlanningPrompts.CONTRACT`；只有 `MysteryProfile` 被注册后，才允许新增其他插件提示词。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=GenreContractServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获授权不 commit。

---

## Task 6: 将 SCHEDULE/角色/卷合同投影为义务并落库

**Files:**

- Create: `novelgen-data/src/main/resources/db/migration-derive/V144__derive_story_obligation.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/DeriveStoryObligationDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/DeriveStoryObligationMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveStoryObligationMapper.xml`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveChapterScheduleService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryObligationPersistenceTest.java`

**Interfaces:**

- Consumes: Task 3 的 `StoryObligationProjector`。
- Produces:

```text
DeriveStoryObligationMapper.insertFull(DeriveStoryObligationDO row)
DeriveStoryObligationMapper.deleteByRun(long runId)
DeriveStoryObligationMapper.selectByRun(long runId)
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
obligationTablePersistsProjectedRows
obligationRowsAreIdempotentByRunAndKey
obligationDeleteByRunClearsRows
obligationStatusDefaultsToPlanned
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationPersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现 V144 和数据层**

`V144__derive_story_obligation.sql`：

```sql
CREATE TABLE derive_story_obligation (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  obligation_key TEXT NOT NULL,
  chapter_no INTEGER NOT NULL,
  kind TEXT NOT NULL,
  target_key TEXT,
  required BOOLEAN NOT NULL DEFAULT FALSE,
  forbidden BOOLEAN NOT NULL DEFAULT FALSE,
  severity TEXT NOT NULL DEFAULT 'HARD',
  source_contract TEXT,
  status TEXT NOT NULL DEFAULT 'PLANNED',
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_user_id BIGINT,
  updated_user_id BIGINT,
  UNIQUE(run_id, obligation_key)
);
CREATE INDEX idx_derive_story_obligation_run_chapter
  ON derive_story_obligation(run_id, chapter_no);
```

Mapper XML 必须显式使用 `::jsonb` 写 `payload`。

- [ ] **Step 4: 修改日程持久化**

`DeriveChapterScheduleService.persist` 在日程写入成功后：

```text
删除旧义务
调用 StoryObligationProjector.project
批量插入新义务
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DeriveStoryObligationPersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 7: ⑥b BATCH 窗口义务校验器

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidator.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/story/DraftWindowObligationValidatorTest.java`

**Interfaces:**

- Consumes: `DeriveStoryObligationMapper.selectByRun`、`StoryObligation`、BATCH 输出的章纲、拍、`state_changes`、`clueEvents`。
- Produces:

```text
DraftWindowObligationValidator.validate(DraftWindow window,
                                        List<StoryObligation> obligations)
    -> List<PlanContracts.PlanIssue>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
rejectsEarlyPayoff
rejectsDuplicatePayoff
rejectsMissingRequiredObligation
rejectsForbiddenEvent
rejectsStateConflict
rejectsKnowledgeReadBeforeWrite
rejectsContractOutsideEntity
acceptsValidWindow
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DraftWindowObligationValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现校验器**

`DraftWindow` 定义为内部 record：

```text
DraftWindow(int fromChapter, int toChapter,
            List<ChapterDraft> chapters,
            List<BeatDraft> beats,
            Map<Integer,List<StateChange>> stateChanges,
            Map<Integer,List<KnowledgeEvent>> knowledgeEvents)
```

校验规则：

- `required=true` 的义务必须在本窗对应章出现；
- `forbidden=true` 的义务不得出现；
- 同一 `targetKey` 的 `PAYOFF_THREAD` 在整本书只能出现一次；
- 线程 payoff 早于义务章 → `OBLIGATION_EARLY`；
- 状态冲突 → `STATE_CONFLICT`；
- `KNOWLEDGE_READ` 早于写入 → `KNOWLEDGE_SOURCE_MISSING`；
- 合同外实体/线程 → `CONTRACT_OUT_OF_SCOPE`。

- [ ] **Step 4: 接入 DeriveDraftService**

在每个 BATCH 输出解析成功、落库前调用：

```text
List<PlanIssue> issues = validator.validate(window, obligations);
if (!issues.isEmpty()) throw new LlmJson.Bad(joinIssues(issues));
```

回炉范围只限当前窗。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=DraftWindowObligationValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 8: P6 改为通用审计 + 类型插件审计

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreGateAuditor.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryGateAuditor.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/MysteryGateAuditorTest.java`

**Interfaces:**

- Consumes: `StoryKernel`、`StoryObligation`、`StoryContract`、`GenreProfile`。
- Produces:

```text
GenreGateAuditor.audit(StoryKernel kernel, GenreProfile profile)
    -> List<PlanContracts.PlanIssue>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
auditorAcceptsClosedKernel
auditorRejectsOpenRequiredThread
auditorRejectsMissingFinalClosure
auditorRejectsMysteryTruthMismatch
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=MysteryGateAuditorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现通用审计**

通用审计检查：

- `required=true` 义务是否完成；
- `forbidden=true` 义务是否违反；
- 必需线程是否关闭；
- `FINAL_CLOSURE` 是否存在；
- 状态和知识是否有硬冲突。

`MysteryGateAuditor` 追加：

- truth 的 culprit/motive/method 是否一致；
- revealChapter 是否越过合同；
- clue plant/payoff 是否一一对应。

- [ ] **Step 4: 修改 DeriveGateService**

`gate()` 在现有合同预检之后调用 `GenreGateAuditor`，将 issue 转成现有 `GateDecision`，不改变现有 `GateVO` API。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=MysteryGateAuditorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 9: QC 改为通用检查 + 类型插件检查

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/GenreQcAuditor.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/MysteryQcAuditor.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/MysteryQcAuditorTest.java`

**Interfaces:**

- Consumes: `StoryKernel`、`StoryObligation`、章节正文、世界状态、`GenreProfile`。
- Produces:

```text
GenreQcAuditor.audit(CompiledDraft draft, StoryKernel kernel,
                     GenreProfile profile) -> List<PlanIssue>
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
rejectsMissingRequiredObligationInText
rejectsWorldStateConflict
rejectsMysteryTruthNotPaidOff
acceptsValidDraft
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=MysteryQcAuditorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现通用 QC 审计**

通用 QC 检查：

- 必需义务是否在正文中兑现；
- 时间线和状态账是否冲突；
- 主线/关系/世界线是否闭合；
- 是否出现合同外实体或线程。

`MysteryQcAuditor` 追加：

- truth 三要素是否兑现；
- clue 是否在正文中出现且未提前泄底；
- reveal 前是否泄露 solution。

- [ ] **Step 4: 修改 DeriveQcService**

在现有脚本硬判后调用 `GenreQcAuditor`，将 issue 合并到现有 `qc_report`，不改变现有报告 API。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=MysteryQcAuditorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 6: 记录提交状态**

未获授权不 commit。

---

## Task 10: 旧 run 兼容适配

**Files:**

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/genre/LegacyMysteryAdapter.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQueryService.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/genre/LegacyMysteryAdapterTest.java`

**Interfaces:**

- Consumes: 旧 `derive_story_contract`、`derive_chapter_schedule`、`derive_volume_plan`、`derive_character_plan`、`beat_instance`。
- Produces:

```text
LegacyMysteryAdapter.toStoryContract(RunDO run) -> StoryContract
LegacyMysteryAdapter.toKernel(StoryContract contract,
                              List<ChapterScheduleEntry> schedule) -> StoryKernel
```

**Implementation:**

- [ ] **Step 1: 写失败测试**

测试：

```text
legacyContractLoadsWithoutGenreColumn
legacyKernelProjectsClueObligations
legacyRunDoesNotRequireNewGenreFields
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl novelgen-service -am "-Dtest=LegacyMysteryAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: 失败。

- [ ] **Step 3: 实现适配器**

旧 run 的 `genre` 为空时固定视为 `MYSTERY`；旧合同映射到 `MysteryProfile`；旧日程通过 `StoryObligationProjector` 投影。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl novelgen-service -am "-Dtest=LegacyMysteryAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected: PASS

- [ ] **Step 5: 记录提交状态**

未获授权不 commit。

---

## Task 11: 真实悬疑短篇探针

**Files:**

- No production code expected.
- Create: `var/derive-probe-mystery-4ch.json`
- Create: `var/derive-probe-mystery-4ch-report.md`

**Interfaces:**

- Consumes: 线上或本地 derive API、登录 cookie、新 run API。
- Produces: 真实 run 证据和报告。

**Implementation:**

- [ ] **Step 1: 准备探针参数**

参数固定为：

```json
{"wordsMin":3000,"wordsMax":5000,"tags":["悬疑"],"pace":"0.75","seed":450067,"genre":"MYSTERY","lenCheck":false}
```

- [ ] **Step 2: 登录并创建 run**

先读 `DeriveRunController` 和 `AuthController`，再用带 `Origin: https://zzdzz.cn:8888` 的请求创建 run。

- [ ] **Step 3: 启动 auto 并轮询**

轮询 `/derive/api/derive/runs/{id}/auto-progress`，记录：

```text
runId
stage
lastError
elapsedMs
contract/schedule/obligation 结果
P6 结果
正文章数
QC 结果
```

- [ ] **Step 4: 验收**

探针只有同时满足以下条件才算通过：

```text
义务表生成
⑥b 窗口校验通过
P6 通过或按设计进入可解释 REWORK
至少 4 章正文非空
QC 报告存在
```

- [ ] **Step 5: 写报告**

报告写入 `var/derive-probe-mystery-4ch-report.md`，不得包含 API key、口令、完整敏感配置。

- [ ] **Step 6: 记录提交状态**

探针产物不提交，除非用户明确要求。

---

## Task 12: 真实非悬疑最小探针

**Files:**

- No production code expected.
- Create: `var/derive-probe-romance-4ch.json`
- Create: `var/derive-probe-romance-4ch-report.md`

**Interfaces:**

- Consumes: `RomanceProfile` 的最小合同能力、derive API。
- Produces: 非悬疑最小探针报告。

**Implementation:**

- [ ] **Step 1: 先实现最小 RomanceProfile**

如果 Task 2 尚未提供 `RomanceProfile`，本任务只允许增加：

```text
RomanceProfile
RomanceProfile 的合同字段
RomanceProfile 的 obligation 投影
```

不得复制一整条新管线。

- [ ] **Step 2: 创建 4 章探针**

参数固定为：

```json
{"wordsMin":3000,"wordsMax":5000,"tags":["言情"],"pace":"0.50","seed":450068,"genre":"ROMANCE","lenCheck":false}
```

- [ ] **Step 3: 运行并验证**

验收：

```text
合同不含 culprit/motive/method/revealChapter
义务包含 relationship/state/choice/closure
⑥b 可生成章纲
P6 可运行
至少 4 章正文非空
QC 报告存在
```

- [ ] **Step 4: 写报告**

报告写入 `var/derive-probe-romance-4ch-report.md`。

- [ ] **Step 5: 记录提交状态**

未获授权不 commit。

---

## Final Verification

全部任务完成后，按顺序执行：

```text
mvn -q test
mvn -q -DskipTests package
git diff --check
```

最终验收必须证明：

- 通用核心没有悬疑专属字段；
- MysteryProfile 是悬疑字段唯一归属；
- 义务投影和窗口校验可运行；
- 旧 run 兼容；
- P6/QC 插件化；
- 悬疑探针和至少一个非悬疑探针有报告；
- 未发生未经授权的 commit、push、部署。
