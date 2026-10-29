# DeriveFlow 合同前置与全链路单上下文 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 ⑤采样、⑥a 规划、⑥b 具体稿、⑦正文、⑧质检始终只引用同一份已冻结故事合同，使 P6 不再承担“发现合同漂移”的职责；新 run 的第一批内容即同源，并能在一次 P6 评审内通过。

**Architecture:** 在 ⑥a 的 `SCALE` 之后、`SPINE` 之前新增 `CONTRACT` 单元，生成并冻结 cast、唯一真相、线索、线程和禁令。`SPINE/VOLUMES/CHARACTERS/SLOT_ROUTE/⑥b/P6/⑦/⑧` 只能读取该合同并做细化，任何节点提出新的 culprit/motive/method、cast 姓名、线程 key 或线索体系都必须被确定性校验拒绝。`derive_story_contract` 表是唯一事实源；`run.step_status.TRUTH_CONTRACT` 只作兼容缓存，不能反向覆盖合同表。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16（`derive_gen`）、Jackson、JUnit 5/Mockito、Flyway。

**Spec:** 本文件；实施前同时读 `docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`、`docs/design/衍生故事规划与节拍路由设计.md`。

## Global Constraints

- 不改主站 `novelgen`；只改随机衍生 `novelgen-derive` 链路。
- 合同一旦 `FROZEN`，P6 不得自动改写合同；只有新建 run、用户明确清空/重置，才允许重新生成。
- `derive_gen` 已存在 `derive_story_contract`（V130），优先复用；若必须加字段，新增 `V138`，禁止改历史迁移。
- `cast` 固定最多 3 个有名角色；`culprit` 必须属于 cast；`maxNamedCharacters=3`。
- 单卷书必须有 `revealChapter`、至少 3 条 clue；多卷书的每卷 truth slice 必须在合同生成时冻结。
- 合同生成失败必须 fail-fast，禁止回退到 `run.welded` 直接规划，禁止静默走旧路径。
- 所有 JSONB 写入必须用 XML 显式 `::jsonb`；禁止用 `List.toString()` 造 JSON。
- 所有 LLM 结构化输出必须通过 `LlmJson` 解析并在 Java 侧二次校验；提示词模板必须在 `PromptCatalog` 登记，否则启动 fail-fast。
- 任何回炉只能修当前目标层；`PLAN_VOLUME:n` 不得清全书，`DRAFT` 不得改合同，`SPINE` 只能从已冻结合同重建。
- 未经用户当轮明确允许，不 commit/push、不部署。

## Review Focus

- 第一批：`SCALE` 后直接进入 `SPINE`，cast/truth 尚未生成，卷合同与章纲按焊接线开工；这是当前 run54/run56/run57 的共同根因。
- 合同漂移：⑥b HEAD C 重新生成 truth，或后续 LLM 改写 culprit/method，P6 才发现两套案情。
- cast 漂移：⑥a 生成 C4/路人，HEAD A 只允许 3 人，卷合同仍引用 C4。
- clue 漂移：真相卡 clue 与章纲由两个 LLM 节点生成，反向校验用前 6 个字逐字包含，语义已兑现仍被硬拦。
- 回炉放大器：P6 `PLAN_VOLUME:n` 进入 ⑥b 的全书 `REWORK` 清稿，局部修复变成整本重生成。
- 合同表被业务模型合并意外清空：`mergeBusinessModel` 不能把 `FROZEN` truth 改成 `NONE`。

---

## 0. 执行前必读与现状保护

**Files:** 无代码改动；先读文件和 `git status`。

- [ ] 读 `最高约束.md`、`AGENTS.md`、`docs/code-standards.md`、`docs/STATUS.md`、`docs/architecture/pipeline-contracts.md`。
- [ ] 执行 `git status --short`。当前工作区可能已有未提交的“角色业务模型”改动，禁止 `git reset --hard`、`git checkout --` 或删除这些文件。
- [ ] 确认当前 `derive_gen` schema 至少为 v137；若低于 v130，先让部署环境应用已有迁移，不要在代码里绕开缺表。
- [ ] 在实施分支（建议 `codex/derive-contract-first`）上工作；不直接改线上数据库。
- [ ] 记录当前线上 jar SHA256 和 `novelgen-derive` 服务状态，部署前备份 jar。

**验收:** 能复述“合同先于 SPINE”的顺序；工作区现有未提交改动仍在。

---

## 1. 建立强类型合同模型与纯校验器

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContract.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContractParser.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContractValidator.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/StoryContractValidatorTest.java`

**Interfaces:**
- Consumes: `PlanContracts.ScopeContract`、`PlanContracts.CharacterFunction`、`DeriveStoryContractDO` JSON 列。
- Produces:
  - `record StoryContract(long runId, int version, String status, List<CharacterSlot> cast, Truth truth, List<Clue> clues, List<ThreadRef> threads, List<String> forbidden, ScopeContract scope)`
  - `record CharacterSlot(String key, String name, String role, String gender, String identity)`
  - `record Truth(String culpritKey, String culpritName, String motive, String method, String finalTruth, String finalChoice, int revealChapter)`
  - `record Clue(String clueKey, String clue, int plantedChapter, int payoffChapter, String ownerKey)`
  - `record ThreadRef(String threadKey, String kind, int openedVolume, int payoffVolume, String ownerKey)`
  - `StoryContractParser.parse(JsonNode root, ScopeContract scope)`
  - `StoryContractValidator.validate(StoryContract contract, ScopeContract scope) -> List<String>`
  - `StoryContractValidator.sameCanonical(StoryContract a, StoryContract b) -> boolean`

- [ ] **Step 1: Write failing tests**

```java
@Test
void exactlyThreeNamedCharactersRequired() { /* 4 cast -> CONTRACT_CAST_LIMIT */ }

@Test
void culpritMustBeInCast() { /* culpritKey=C9 -> CONTRACT_CULPRIT_NOT_IN_CAST */ }

@Test
void clueChaptersMustBeInScopeAndPayoffAfterPlant() { /* 0/越界/payoff<=plant -> CONTRACT_CLUE_RANGE */ }

@Test
void frozenContractCannotChangeCulpritOrMethod() { /* sameCanonical false -> CONTRACT_DRIFT */ }
```

- [ ] **Step 2: Run tests and verify failure**

Run:
```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am "-Dtest=StoryContractValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```
Expected: compilation/test failure because classes do not exist.

- [ ] **Step 3: Implement records and pure validation**

Validation rules:

```text
cast.size() must be 1..3 and exactly 3 when maxNamedCharacters=3
cast.key unique, cast.name unique and nonblank
truth.culpritKey must be in cast
truth.culpritName must equal cast[culpritKey].name
truth.motive/method/finalTruth nonblank
truth.revealChapter in 1..scope.chapterCount
clues.size() >= 3
each clue.clueKey matches GM-CLUE-\d+
each clue.chapter in range and payoffChapter > plantedChapter
each thread.threadKey unique and payoffVolume in 1..scope.volumeCount
forbidden is a JSON array of strings, never a JSON-encoded string
```

- [ ] **Step 4: Run tests and verify pass**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContract.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContractParser.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/contract/StoryContractValidator.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/contract/StoryContractValidatorTest.java
git commit -m "feat(derive): add canonical story contract model"
```

---

## 2. 合同生成服务：在 ⑥a 之前生成并冻结

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- Modify: `novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/PromptCatalog.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveContractServiceTest.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/ContractPromptArityTest.java`

**Interfaces:**
- Consumes: `StoryContract`、`LlmJson`、`ScaffoldContract`、`RunDO`。
- Produces:
  - `StoryContract generateIfAbsent(RunDO run, ScopeContract scope, ScaffoldContract scaffold)`
  - `StoryContract load(RunDO run)`
  - `StoryContract require(RunDO run)`
  - `void assertUnchanged(StoryContract before, StoryContract after, String stage)`
  - `String promptContext(StoryContract contract)` 

- [ ] **Step 1: Write failing tests**

```java
@Test
void contractGenerationIsIdempotent() {
    // second call returns same version; no second insert
}

@Test
void contractGenerationRejectsFourNames() {
    // LLM fixture 4 cast -> LlmJson.Bad / CONTRACT_CAST_LIMIT
}

@Test
void frozenContractIsNeverReplacedByBusinessModelMerge() {
    // mergeBusinessModel must preserve FROZEN truth
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:
```powershell
mvn -q test -pl novelgen-service -am "-Dtest=DeriveContractServiceTest,ContractPromptArityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Add `derive_contract` prompt**

In `StoryPlanningPrompts`:

```java
public static final String NODE_CONTRACT = "derive_contract";
public static final String CONTRACT = """
你在为一部小说冻结唯一故事合同，不写卷纲、章纲或正文。
输入含：创作框架、类别、结局形状、产品形态、焊接主线、scaffold facts、章数/卷数、maxNamedCharacters。
你的输出一旦冻结，后续所有规划、章纲、P6、正文都必须服从它。
要求：
1. 恰好 3 名有名角色；cast.key 固定 C1/C2/C3；主角为 C1；OPPOSITION 必须是其中一名；
2. culpritKey 必须是 cast.key；culpritName 必须等于该 cast.name；
3. 只允许一个主案；motive/method/finalTruth 必须能解释全部关键事实；
4. revealChapter 必须在 1..chapterCount；clues 至少 3 条，plantedChapter/payoffChapter 均在范围内且 payoff > plant；
5. forbidden 必须是 JSON 字符串数组，禁止写成字符串化的 JSON；
6. 多卷时必须给每卷 truthSlice，卷号连续覆盖 1..volumeCount；
7. 不得引入焊接主线之外的新主案、第二起案件、死亡或超自然解释，除非创作框架明确允许。
只输出 JSON 对象：
{"cast":[{"key":"C1","name":"…","role":"DETECTIVE","gender":"…","identity":"…"},
          {"key":"C2","name":"…","role":"ACCESS","gender":"…","identity":"…"},
          {"key":"C3","name":"…","role":"OPPOSITION","gender":"…","identity":"…"}],
 "truth":{"culpritKey":"C3","culpritName":"…","motive":"…","method":"…",
          "finalTruth":"…","finalChoice":"…","revealChapter":12},
 "clues":[{"clueKey":"GM-CLUE-01","clue":"可观测现象","plantedChapter":3,"payoffChapter":11,"ownerKey":"C1"}],
 "threads":[{"threadKey":"GM-CLUE-01","kind":"CLUE","openedVolume":1,"payoffVolume":1,"ownerKey":"C1"}],
 "forbidden":["…"],
 "volumeTruth":[{"volumeNo":1,"reveal":"…","forbidden":["…"]}]}
""";
```

Register in `PromptCatalog.ALL` with node `derive_contract`, phase `system`.

- [ ] **Step 4: Implement generate/load/require**

`generateIfAbsent`:

1. `load(run)` non-null -> return it.
2. `llmJson.ask(new LlmPort.ChatRequest(NODE_CONTRACT, ...), root -> StoryContractParser.parse(root, scope), 3)`.
3. `StoryContractValidator.validate` non-empty -> throw `LlmJson.Bad` and retry.
4. Persist one `derive_story_contract` row with `status=FROZEN`, `version=1`, JSON columns:
   - `character_slots` = cast
   - `truth_kernel` = truth
   - `clue_registry` = clues
   - `event_kernel` = volumeTruth
   - `constraint_kernel` = `{forbidden:[...], hardLimits:{...}, volumeTruth:[...]}` 
5. Write `run.step_status.steps.TRUTH_CONTRACT` and `STORY_CONTRACT` from this row; `TRUTH_CONTRACT` is compatibility cache only.
6. Do not call `freezeTruth` from ⑥b anymore; rename/replace it with `assertContractUnchanged`.

- [ ] **Step 5: Fix existing JSON string bugs**

In `DeriveContractService`:

- `constraints.forbidden` must be `ArrayNode`, not `put(String)`.
- `hardLimits` must be an object node.
- `clues` must be an array, never `put("clues", "[]")`.
- `mergeBusinessModel` must never change `FROZEN` truth to `NONE`; only `PROVISIONAL` may become `NONE`.
- `mergeBusinessModel` must write `current.version + 1`.

- [ ] **Step 6: Run tests**

Run the Step 2 command; expected PASS. Add `findAndRegisterModules()` only to serialization mappers that serialize DOs; do not serialize DOs into prompts when a projection is available.

- [ ] **Step 7: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java \
        novelgen-llm/src/main/java/com/zzdzz/novelgen/llm/PromptCatalog.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveContractServiceTest.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/ContractPromptArityTest.java
git commit -m "feat(derive): generate canonical contract before planning"
```

---

## 3. 把 `CONTRACT` 插入 PLAN_V2 状态机

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanContractTest.java`

**Interfaces:**
- Consumes: `DeriveContractService.generateIfAbsent`、`ScaffoldContract`。
- Produces:
  - `PlanUnit.CONTRACT` enum value
  - `PlanVO advanceContract(RunDO run, PlanV2State oldState)`
  - `JsonNode canonicalContract(RunDO run)` (contract table first, step_status cache only as fallback)

- [ ] **Step 1: Write failing transition tests**

```java
@Test
void scaleTransitionsToContractBeforeSpine() {
    // advanceScale -> currentUnit CONTRACT
}

@Test
void contractUnitCreatesExactlyOneFrozenContract() {
    // advanceContract twice -> same version, no insert
}

@Test
void spineCannotRunWithoutFrozenContract() {
    // no contract -> BizException CONTRACT_REQUIRED
}
```

- [ ] **Step 2: Implement state transition**

`PlanContracts.PlanUnit`:

```java
SCALE, CONTRACT, SPINE, VOLUMES, CHARACTERS, SLOT_ROUTE, VALIDATE, NEED_SCOPE, DONE, LEGACY
```

`advanceScale`:

- after `writeSubContract(run, "SCAFFOLD", scaffold)` and novel creation,
- when scope is not `NEED_CONFIRMATION`, set next:

```java
new PlanV2State(PlanUnit.CONTRACT,
        append(oldState.completedUnits(), PlanUnit.SCALE), scope, oldState.volumeUnits());
```

`confirmScope` next unit must be `CONTRACT`, not `SPINE`.

`advanceContract`:

```java
StoryContract contract = contractService.generateIfAbsent(run, oldState.scope(),
        readScaffold(run));
writePlanState(run, new PlanV2State(PlanUnit.SPINE,
        append(oldState.completedUnits(), PlanUnit.CONTRACT),
        oldState.scope(), oldState.volumeUnits()));
return planVO(run, oldState.scope(), "CONTRACT", false);
```

`advanceSpine`:

- `StoryContract contract = contractService.require(run);`
- `spineFromContract(contract)` deterministic; no LLM if contract exists.
- If contract missing, throw `BizException(ErrorCode.PARAM_ERROR, "CONTRACT_REQUIRED")`; never fallback to `run.welded`.

- [ ] **Step 3: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=DeriveStoryPlanContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanContractTest.java
git commit -m "feat(derive): add contract stage before spine"
```

---

## 4. 让 SPINE/VOLUMES/CHARACTERS 只细化合同，不重新发明

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/PlanContractConsistencyTest.java`

**Interfaces:**
- Consumes: `StoryContract`、`StoryContractValidator`。
- Produces:
  - `spineFromContract(StoryContract) -> PlanContracts.StorySpine`
  - `volumeFromContract(StoryContract, int volumeNo) -> PlanContracts.VolumeContract`
  - `validateCharactersAgainstContract(List<CharacterPlanDO>, StoryContract) -> List<String>`

- [ ] **Step 1: Write failing tests**

```java
@Test
void volumeTrueAnswerMustEqualContractTruth() { /* mutated trueAnswer -> CONTRACT_DRIFT */ }

@Test
void characterNamesMustMatchContractCast() { /* C4 -> CONTRACT_CAST_DRIFT */ }

@Test
void characterFunctionMustMatchContractSlot() { /* C1->ACCESS -> CONTRACT_ROLE_DRIFT */ }

@Test
void volumeThreadsMustComeFromContract() { /* new GM-* -> CONTRACT_THREAD_DRIFT */ }
```

- [ ] **Step 2: Revise prompts**

In `SPINE` prompt:

- input contains `FROZEN_CONTRACT`;
- output may only split contract truth into volume truth ladder;
- `finalTruth` must be copied from contract, not rewritten;
- `globalThreads` keys must be a subset of contract threads.

In `VOLUMES` prompt:

- `trueAnswer` must equal the contract truth slice for that volume;
- `forbiddenReveals` must be a subset of contract forbidden;
- `requiredThreads` only contract thread keys;
- no new case or character.

In `CHARACTERS` prompt:

- cast keys/names/functions fixed by contract;
- only fill arcs, wants, flaws, highlight choices, conflict targets;
- no new character key; no rename.

- [ ] **Step 3: Implement deterministic reconciliation**

Single-volume:

```java
volumeFromContract(contract, 1) -> exactly contract.truth.finalTruth as trueAnswer/arc/localClosureProof,
  contract.threads as requiredThreads, contract.forbidden as forbiddenReveals
```

Multi-volume:

- contract must contain `volumeTruth` for every volume;
- LLM may only write `localQuestion/falseAnswer/mainMysteryAdvance` fillers;
- after parse, replace `trueAnswer` with contract slice and reject any changed solution.

After `persistCharacters`, run:

```java
List<String> errors = contractValidator.validateCharacters(characters, contract);
if (!errors.isEmpty()) throw new PlanJson.Bad(String.join(";", errors));
```

- [ ] **Step 4: Remove stale fallbacks**

- Delete/stop using `temporaryCharacters(run)` for real planning; use contract.cast.
- Delete `run.getWelded()` fallback in `putMainline` when contract is present.
- Delete P6 `HEAD_C_TRUTH_CONFLICT` gate; it must not be reachable after contract-first.

- [ ] **Step 5: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=PlanContractConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 6: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/PlanContractConsistencyTest.java
git commit -m "feat(derive): constrain planning to frozen contract"
```

---

## 5. ⑥b HEAD A/B/C 只做合同物化

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java` if head prompts live there
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveDraftContractTest.java`

**Interfaces:**
- Consumes: `StoryContract`、`contract.cast`、`contract.truth`、`contract.clues`。
- Produces:
  - `void materializeCastFromContract(RunDO, NovelDO)`
  - `void validateHeadC(JsonNode out, StoryContract contract)`
  - `void validateBatch(JsonNode out, StoryContract contract)`

- [ ] **Step 1: Write failing tests**

```java
@Test
void headACannotRenameOrAddContractCast() { /* name change -> CONTRACT_CAST_DRIFT */ }

@Test
void headCCannotChangeCulpritMotiveMethod() { /* changed method -> CONTRACT_DRIFT */ }

@Test
void headCCannotChangeRevealChapter() { /* changed reveal -> CONTRACT_DRIFT */ }

@Test
void batchCannotIntroduceUnknownName() { /* "陈先生" -> CONTRACT_NAME_DRIFT */ }
```

- [ ] **Step 2: HEAD A**

Replace `HEAD A` cast generation with:

```java
StoryContract contract = contractService.require(run);
String protagonistName = contract.cast().stream()
        .filter(slot -> "C1".equals(slot.key()))
        .findFirst().orElseThrow().name();
novelMapper.updateCast(novelId, protagonistName,
        STATIC_MAPPER.writeValueAsString(contract.cast()));
```

`protagonistName = contract.cast key C1 name`. LLM may only generate title/summary; if output contains a different cast, reject/ignore cast and keep contract.

- [ ] **Step 3: HEAD C**

Input must include `FROZEN_CONTRACT`.

Output may contain:

```json
{"brief":{"premise":"…","ending":"…"},
 "truth":{"reveal_chapter":12,
          "clues":[{"clueKey":"GM-CLUE-01","clue":"…","planted_chapter":3}],
          "solution":{"culprit":"…","motive":"…","method":"…"}}}
```

After parse:

- `solution.culprit` must equal contract.truth.culpritName;
- `solution.motive/method` must be identical or accepted as a faithful copy; use exact string equality for first pass;
- `reveal_chapter` equals contract.truth.revealChapter;
- clueKey set equals contract clueKey set;
- planted/payoff chapter values equal contract;
- any mismatch -> retry max 3; still fail with `CONTRACT_HEAD_C_DRIFT`, do not call `freezeTruth`.

- [ ] **Step 4: BATCH**

Prompt includes contract cast/truth/clues/threads. Post-parse deterministic checks:

- every named entity (regex over `filled`/`draft_outline`) must be in cast; unknown names -> `CONTRACT_NAME_DRIFT`;
- `draft_outline` before `revealChapter` must not contain `culpritName` + `method` conclusion;
- every `threadRef` is a contract thread key;
- every clue is planted/payoff exactly once at contract chapter or contract range;
- no new GM-/REL-/OBJ- key.

- [ ] **Step 5: Remove `planUsesDifferentTruth` and `frozenTruthCard` as truth source**

- `planUsesDifferentTruth` must be deleted once contract-first is active; it caused repeated SPINE rebuild loops.
- `frozenTruthCard` may remain only as a read-only view for tests; `freezeTruth` must not insert new contract in ⑥b.

- [ ] **Step 6: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=DeriveDraftContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 7: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveDraftContractTest.java
git commit -m "feat(derive): bind draft heads to frozen contract"
```

---

## 6. P6 只做质量评审，不做合同发现

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateContractTest.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationServiceTest.java`

**Interfaces:**
- Consumes: `DeriveContractService.require(run)`、`StoryContractValidator`。
- Produces:
  - `GateTruth` includes exactly one `StoryContract`
  - `validateContractAgainstPlan(StoryContract, volumes, characters, chapters)`
  - `CLUE_PLANT_MISMATCH` only as WARN, never a P6 hard error

- [ ] **Step 1: Write failing tests**

```java
@Test
void gateInputHasOneCanonicalContractAndNoStaleSpineTruth() { /* buildUser */ }

@Test
void contractDriftFailsBeforeLlmGate() { /* target CONTRACT_INVALID */ }

@Test
void clueTextDifferentButKeyMatchesIsNotHardError() { /* WARN only */ }

@Test
void planVolumeReworkDoesNotChangeContractVersion() { /* same version */ }
```

- [ ] **Step 2: Rewrite `resolveGateTruth`**

```java
StoryContract contract = contractService.require(run);
return new GateTruth(contract, volumesOf(contract));
```

Do not merge forbidden/threads from stale `STORY_SPINE` or old volume contracts. BuildUser must set exactly one `FROZEN_CONTRACT`; remove `主线` fallback to welded when contract exists.

- [ ] **Step 3: Preflight contract validation**

Before `derive_gate` LLM:

```java
List<String> contractIssues = contractValidator.validateAgainstPlan(contract, volumes, characters, chapters);
if (!contractIssues.isEmpty()) {
    runMapper.updateStepStatus(... GATE FAILED ... "CONTRACT_INVALID");
    runMapper.updateStatus(... "FAILED");
    return GateVO(... "FAILED", "CONTRACT_INVALID", ...);
}
```

Only plan-level drift (volume/character/chapter) may produce `REWORK PLAN_VOLUME:n`; contract-level drift is terminal because it means an upstream node violated the frozen contract.

- [ ] **Step 4: Fix clue validator**

Replace first-6-character contains with structured validation:

- clue key must be in `contract.clues`;
- chapter must equal contract planted/payoff;
- chapter plan must contain the clue key in `plan_contract` or the beat route `threadRefs`;
- if only prose wording differs, emit `WARN CLUE_PROSE_DIFF`, never `ERROR`.

- [ ] **Step 5: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=DeriveGateContractTest,DerivePlanValidationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 6: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateContractTest.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationServiceTest.java
git commit -m "feat(derive): make gate contract-aware, not contract-discovering"
```

---

## 7. 回炉与清空：合同不可被局部重规划改写

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveResetService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveResetServiceTest.java`

**Interfaces:**
- Consumes: `DeriveContractService`、`DeriveStoryContractMapper`。
- Produces:
  - `clearPlanning` deletes contract row and resets PLAN_V2 to SCALE
  - `replanFrom("SPINE")` preserves frozen contract
  - `replanFrom("SCALE")` deletes contract and restarts at SCALE

- [ ] **Step 1: Write failing tests**

```java
@Test
void clearPlanningDeletesContract() { /* contract table count 0 */ }

@Test
void replanSpineKeepsContractVersion() { /* version unchanged */ }

@Test
void replanScaleDeletesContract() { /* next run creates new version 1 */ }
```

- [ ] **Step 2: Implement**

- Inject `DeriveStoryContractMapper` into `DeriveResetService`.
- `clearPlanning`: delete contract rows for run; `PLAN_V2` initial = SCALE.
- `replanFrom(SPINE)`: keep contract; `resetPlanState` must not remove `TRUTH_CONTRACT`/`STORY_CONTRACT` cache.
- `replanFrom(SCALE)`: delete contract; reset to initial.
- `DeriveAutoFlowService`: delete `PLAN_VOLUME` full-draft behavior; if target `PLAN_VOLUME:n`, only `replanVolume` + regenerate that volume's chapter outlines; never call `draftService.draft` on entire book.

- [ ] **Step 3: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=DeriveResetServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveResetService.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveResetServiceTest.java
git commit -m "fix(derive): preserve contract across local rework"
```

---

## 8. 旧 run 兼容与迁移

**Files:**
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/LegacyContractCompatibilityTest.java`

**Interfaces:**
- Consumes: existing `PLAN_V2` state and `derive_story_contract` rows.
- Produces: `boolean needsContractBackfill(RunDO)`、`void resetLegacyPlanningToContract(RunDO)`

- [ ] **Step 1: Write tests**

```java
@Test
void legacyRunWithoutContractAndWithoutTextRestartsAtContract() { /* currentUnit CONTRACT */ }

@Test
void runWithWrittenTextIsNotAutoMigrated() { /* BizException LEGACY_TEXT_REQUIRES_MANUAL */ }

@Test
void existingFrozenContractIsReused() { /* no new version */ }
```

- [ ] **Step 2: Implement**

- If run has no contract and no正文:
  - `PLAN_V2.currentUnit` => CONTRACT;
  - preserve SCALE scope/scaffold;
  - do not delete already-generated data until contract stage succeeds? Safer: delete volume/character/chapter/beat rows only after contract is generated, then rebuild.
- If run has正文: do not auto-migrate; return clear error and require new run.
- Historical `TRUTH_CONTRACT` without contract row: create contract from existing truth card only if `truth` is complete and validator passes; otherwise reset to CONTRACT.

- [ ] **Step 3: Run tests**

```powershell
mvn -q test -pl novelgen-service -am "-Dtest=LegacyContractCompatibilityTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: Commit**

```bash
git add novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java \
        novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveContractService.java \
        novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/LegacyContractCompatibilityTest.java
git commit -m "feat(derive): backfill contract for safe legacy runs"
```

---

## 9. 全量测试与静态检查

**Files:** 无新增代码。

- [ ] **Step 1: Targeted tests**

```powershell
$env:JAVA_HOME="D:\Program\Java\jdk-21"
mvn -q test -pl novelgen-service -am `
  "-Dtest=StoryContractValidatorTest,DeriveContractServiceTest,ContractPromptArityTest,DeriveStoryPlanContractTest,PlanContractConsistencyTest,DeriveDraftContractTest,DeriveGateContractTest,DerivePlanValidationServiceTest,DeriveResetServiceTest,LegacyContractCompatibilityTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 2: Full suite**

```powershell
mvn -q test
```

- [ ] **Step 3: Build derive app only**

```powershell
mvn -q -pl novelgen-derive-app -am package -DskipTests
```

- [ ] **Step 4: Verify no prompt/catalog mismatch**

Start locally or inspect `PromptCatalog.ALL` count; `derive_contract` must appear exactly once, and every `StoryPlanningPrompts.NODE_*` used by `llmJson.ask` must have a catalog entry.

- [ ] **Step 5: Commit**

```bash
git add docs/STATUS.md
git commit -m "docs(status): record contract-first implementation"
```

---

## 10. 宝塔实跑验收

**Files:** 无代码改动；部署 `novelgen-derive-app` only。

**Preconditions:** 用户当轮明确授权部署；记录旧 jar SHA256；服务空闲。

- [ ] **Step 1: Deploy**

```powershell
$K="keys\101.126.22.219_id_ed25519"
$H="root@101.126.22.219"
ssh -i $K $H "cp -a /www/wwwroot/novelgen-derive/novelgen-derive-app.jar /www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-contract-first"
ssh -i $K $H "systemctl stop novelgen-derive"
scp -i $K novelgen-derive-app\target\novelgen-derive-app-0.1.0-SNAPSHOT.jar ${H}:/www/wwwroot/novelgen-derive/novelgen-derive-app.jar
ssh -i $K $H "chown www:www /www/wwwroot/novelgen-derive/novelgen-derive-app.jar; systemctl start novelgen-derive"
```

Verify:

```bash
ssh -i keys/101.126.22.219_id_ed25519 root@101.126.22.219 \
  "systemctl is-active novelgen-derive; ss -tlnp | grep 8091; tail -20 /www/wwwroot/novelgen-derive/logs/app.log"
```
Expected: active, 8091 listen, Flyway no new migration (or V138 success if added), no startup exception.

- [ ] **Step 2: Create a fresh run**

Use `POST /derive/api/derive/runs` with a new seed and the same 1.2–1.8 万字悬疑单卷 fixture; record returned `runId`. Do not reuse run54/56/57.

- [ ] **Step 3: Start auto flow**

`POST /derive/api/derive/runs/{id}/auto?direct=false`.

- [ ] **Step 4: Assert stage order**

Poll `/auto-progress`; expected:

```text
SCALE → CONTRACT → SPINE → VOLUMES → CHARACTERS → SLOT_ROUTE → ⑥b HEAD → BATCH → P6
```

`HEAD_C_TRUTH_CONFLICT` must never appear. If it appears, stop the run and treat as implementation failure, not retry.

- [ ] **Step 5: Assert contract stability**

```sql
SELECT version,status,
       truth_kernel->>'culprit' AS culprit,
       truth_kernel->>'finalTruth' AS final_truth
FROM derive_story_contract
WHERE run_id = <runId>
ORDER BY version;

SELECT round,verdict,action,repair_target
FROM derive_gate_review
WHERE run_id = <runId>
ORDER BY round;

SELECT step_status->'steps'->'TRUTH_CONTRACT',
       step_status->'steps'->'STORY_SPINE'
FROM run WHERE id = <runId>;
```

Pass criteria:

- exactly one contract version (`1 FROZEN`) for the fresh run;
- `culprit` never changes from CONTRACT to P6;
- `STORY_SPINE.finalTruth` equals `TRUTH_CONTRACT.finalTruth`;
- P6 round 1 is `GO/PASS`, or at most one `REWORK` caused only by semantic quality with target `DRAFT`/`PLAN_VOLUME:1`;
- no `CLUE_PLANT_MISMATCH` in hard errors;
- no new names beyond the 3 contract cast names;
- `derive_story_contract` version count does not grow during P6 rework.

- [ ] **Step 6: If P6 fails**

Do not loop/deploy again. Collect:

- run status and `step_status.steps.GATE`;
- all `derive_gate_review` rows;
- contract versions and truth kernels;
- volume/character/chapter plan rows;
- `llm_call_log` errors for the run.

Return the exact first divergence stage. The correct next action is to fix the violating stage, not to relax P6.

- [ ] **Step 7: Final acceptance**

Run must reach `P6 PASS` (not `direct=true`). Then allow ⑦ first chapter to verify it uses the same contract; ⑧ is separate acceptance and may be a later run if the user only asked for P6.

---

## 11. Rollback

- Restore `/www/wwwroot/novelgen-derive/novelgen-derive-app.jar.bak-contract-first`.
- `systemctl restart novelgen-derive`.
- No DB rollback needed because this plan adds no destructive migration; if a V138 was added, leave the additive column and document it.
- Existing runs remain readable; new contract-first runs use the new path.

---

## 12. Definition of Done

- [ ] Fresh run has exactly one `FROZEN` contract.
- [ ] `CONTRACT` stage precedes `SPINE` in `/auto-progress`.
- [ ] No contract version change after `CONTRACT`.
- [ ] HEAD A/B/C, volume plan, character plan, route and batch all reference the same cast/culprit/method/clue keys.
- [ ] P6 round 1 passes with no hard contract conflict; only semantic advisories are allowed.
- [ ] No `HEAD_C_TRUTH_CONFLICT` and no hard `CLUE_PLANT_MISMATCH`.
- [ ] Full `mvn -q test` passes and derive jar builds.
- [ ] Deployment verified on 宝塔 and new run reaches P6 PASS without `direct=true`.

