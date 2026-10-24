# 衍生故事规划与节拍路由 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把随机衍生 ⑥a 从“定规模后按数量切拍”升级为可校验的故事、卷、角色、线程与节拍规划系统，并让 ⑥b/P6/⑧消费同一份结构化计划。

**Architecture:** 新增 `DeriveStoryPlanService`、`DeriveBeatRouteService`、`DerivePlanValidationService` 三个深模块；`DerivePlanService` 只保留幂等状态机编排。数据层新增角色计划和故事线程 2 张表，扩展现有卷、章和拍实例合同；⑦逐章写正文主流程不变。

**Tech Stack:** Java 21、Spring Boot 3.5.5、MyBatis-Plus、PostgreSQL 16、Flyway、Jackson、Vue 3、Vite、JUnit 5、Mockito。

**Spec:** `docs/design/衍生故事规划与节拍路由设计.md`

## Global Constraints

- Maven 6 模块依赖只允许 `app→service→{llm,data}→{model,common}`、`llm→data`。
- DO=库实体，DTO=入参，VO=出参；库实体不得逸出 service 层。
- SQL 只允许出现在 `novelgen-data/src/main/resources/mapper/*.xml`。
- JSONB 写入必须显式 `#{value}::jsonb`。
- 新增迁移固定为 `V121__derive_story_plan_contracts.sql`，不得改 V1-V120。
- 历史 run 的 `step_status.steps.PLAN` 保持可读，不自动迁移或重算。
- 新 run 使用 `step_status.steps.PLAN_V2`；旧 run 继续走旧计划读取路径。
- 实时角色状态继续由 `world_states` 负责，不新增角色状态表。
- 新业务模块使用具体 `@Service` 类；只有一个实现时不造接口和工厂。
- 不新增微服务、消息队列、图数据库或第三方依赖。
- ⑦逐章写正文、断点续跑和停止语义本阶段不变。
- 未经用户当轮明确授权，禁止 `git add`、commit、push、部署、重启线上服务。
- 管线运行中禁止 `mvn clean` 和重启后端；只允许 `mvn compile/test`。
- 每个任务完成后不自动提交，只报告建议提交信息和变更文件。

## Review Focus

- 用户显式写“四卷”时，规模判断不得因字数带或拍数改成一至三卷；Task 3 必须锁定。
- 已死亡角色以 `LIVE` 形式再次行动时必须被拒绝，回忆/遗体/转述等合法形式必须通过；Task 7 必须锁定。
- 角色不能使用尚未获得的知识，尤其不能提前使用真相卡 solution；Task 7 必须锁定。
- 非末卷无钩子或最终卷仍有必需 OPEN 线程时必须失败；Task 7 必须锁定。
- ⑥a 单元中途失败、重复调用或单卷重规划不得重复插拍、污染其他卷或丢失已完成路线；Task 6 与 Task 8 必须锁定。

## File Structure

### 数据与实体

- Create: `novelgen-data/src/main/resources/db/migration-derive/V121__derive_story_plan_contracts.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/CharacterPlanDO.java`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/StoryThreadDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/CharacterPlanMapper.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/StoryThreadMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveCharacterPlanMapper.xml`
- Create: `novelgen-data/src/main/resources/mapper/DeriveStoryThreadMapper.xml`
- Create: `novelgen-data/src/main/resources/mapper/DeriveBeatInstanceMapper.xml`
- Create: `novelgen-data/src/main/resources/mapper/DeriveVolumePlanMapper.xml`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/VolumePlanDO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/ChapterDO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/BeatInstanceDO.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/VolumePlanMapper.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/ChapterMapper.java`
- Modify: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/BeatInstanceMapper.java`
- Modify: `novelgen-data/src/main/resources/mapper/DeriveChapterMapper.xml`

### 规划合同

- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanV2StateCodec.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanJson.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteService.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationService.java`

### 现有业务接入

- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DerivePlanService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveDraftService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveWriteService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveAutoFlowService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveGateService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQcService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveQueryService.java`
- Modify: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveResetService.java`

### API、VO 与前端

- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/dto/derive/PlanScopeConfirmDTO.java`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/StoryPlanDetailVO.java`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/BeatRouteVO.java`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/PlanValidationVO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/PlanVO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/GateVO.java`
- Modify: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/QcVO.java`
- Modify: `novelgen-derive-app/src/main/java/com/zzdzz/novelgen/derive/web/DeriveRunController.java`
- Modify: `web/src/views/derive/DeriveRunDetailView.vue`
- Modify: `web/src/views/derive/deriveLabels.js`

### 测试

- Create: `novelgen-data/src/test/java/com/zzdzz/novelgen/derive/mapper/DeriveV121MigrationContractTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanContractsCodecTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryScaleV2Test.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStorySpineTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveVolumeCharacterPlanTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveBeatRouteServiceTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePlanValidationServiceTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DerivePlanOrchestrationV2Test.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveDraftPlanConsumptionTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveGateQcStoryPlanTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanQueryTest.java`
- Create: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanValidationFixtureTest.java`
- Modify: existing `DerivePlanWindowTest`、`DeriveDraftBatchTest`、`DeriveQueryServiceTest` only where constructor or VO shape changes.
- Modify: existing `DeriveWorldStateInjectTest` for the new `characters` snapshot contract.

---

### Task 1: V121 Schema and Persistence Adapters

**Files:**
- Create: `novelgen-data/src/main/resources/db/migration-derive/V121__derive_story_plan_contracts.sql`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/CharacterPlanDO.java`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/entity/derive/StoryThreadDO.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/CharacterPlanMapper.java`
- Create: `novelgen-data/src/main/java/com/zzdzz/novelgen/derive/mapper/StoryThreadMapper.java`
- Create: `novelgen-data/src/main/resources/mapper/DeriveCharacterPlanMapper.xml`
- Create: `novelgen-data/src/main/resources/mapper/DeriveStoryThreadMapper.xml`
- Create: `novelgen-data/src/main/resources/mapper/DeriveBeatInstanceMapper.xml`
- Modify: `VolumePlanDO`、`ChapterDO`、`BeatInstanceDO`
- Modify: `VolumePlanMapper`、`ChapterMapper`、`BeatInstanceMapper`、`DeriveChapterMapper.xml`
- Test: `novelgen-data/src/test/java/com/zzdzz/novelgen/derive/mapper/DeriveV121MigrationContractTest.java`

**Interfaces:**
- Produces: `CharacterPlanMapper.insertFull(CharacterPlanDO) -> long`
- Produces: `StoryThreadMapper.insertFull(StoryThreadDO) -> long`
- Produces: `VolumePlanMapper.updateContract(long, String) -> int`
- Produces: `ChapterMapper.updatePlanContract(long, String) -> int`
- Produces: `BeatInstanceMapper.updateRoute(BeatInstanceDO) -> int`
- Produces: `ChapterMapper.clearPlanByVolume(long, int) -> int`
- Produces: `BeatInstanceMapper.clearByVolume(long, int) -> int`

- [ ] **Step 1: Write the failing migration and entity contract test**

```java
class DeriveV121MigrationContractTest {
    @Test
    void v121DefinesStoryContractsWithoutDuplicatingWorldState() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration-derive/V121__derive_story_plan_contracts.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("CREATE TABLE derive_character_plan"));
        assertTrue(sql.contains("CREATE TABLE derive_story_thread"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS contract JSONB"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS plan_contract JSONB"));
        assertTrue(sql.contains("SYNTHETIC"));
        assertFalse(sql.contains("CREATE TABLE derive_character_state"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-data -am "-Dtest=DeriveV121MigrationContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL because V121 and the test do not exist.

- [ ] **Step 3: Implement the schema and adapters**

Use the DDL from spec §5.1–§5.5 verbatim. Add DO fields using existing `DeriveBaseDO`.
Mapper XML must use `#{p.contract}::jsonb`, `#{p.routeContract}::jsonb`, and explicit `NOW()` for `updated_time`.
Do not use MP `save/updateById` for JSONB fields.

- [ ] **Step 4: Run migration and mapper tests**

Run:

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-data -am "-Dtest=DeriveV121MigrationContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q compile
```

Expected: PASS and compile success.

- [ ] **Step 5: Verification checkpoint**

Run `git diff --check -- <task files>` and inspect the V121 SQL.
Suggested commit message for later authorization: `feat: add story planning contracts`.

---

### Task 2: Plan Contracts and PLAN_V2 State Codec

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanContracts.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanV2StateCodec.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/plan/PlanContractsCodecTest.java`

**Interfaces:**
- Produces: `PlanContracts.PlanUnit` values `SCALE, SPINE, VOLUMES, CHARACTERS, SLOT_ROUTE, VALIDATE, NEED_SCOPE, DONE, LEGACY`
- Produces: `PlanContracts.ScopeDecision` values `SINGLE_VOLUME, MULTI_VOLUME, NEED_CONFIRMATION`
- Produces: `PlanContracts.StoryLayer` values from spec §5.5
- Produces: `PlanContracts.BeatRole` values from spec §5.5
- Produces: `PlanContracts.AppearanceMode` values from spec §5.5
- Produces: `PlanContracts.PlanIssue(level, code, repairScope, volumeNo, chapterNo, seqs, message)`
- Produces: `PlanContracts.PlanV2State(currentUnit, completedUnits, scope, volumeUnits)`
- Produces: `PlanV2StateCodec.read(String) -> PlanV2State`
- Produces: `PlanV2StateCodec.write(PlanV2State) -> String`

- [ ] **Step 1: Write the failing codec test**

```java
@Test
void planV2StateRoundTripsAndPreservesWireValues() {
    var state = new PlanContracts.PlanV2State(
            PlanContracts.PlanUnit.SPINE,
            List.of(PlanContracts.PlanUnit.SCALE),
            new PlanContracts.ScopeContract(
                    PlanContracts.ScopeDecision.MULTI_VOLUME, 4, 48, 2800,
                    List.of("显式四卷")),
            Map.of(1, PlanContracts.PlanUnit.SLOT_ROUTE));
    String json = PlanV2StateCodec.write(state);
    assertEquals(state, PlanV2StateCodec.read(json));
    assertTrue(json.contains("\"MULTI_VOLUME\""));
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=PlanContractsCodecTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL because the contract classes do not exist.

- [ ] **Step 3: Implement contracts and codec**

Use nested public records inside `PlanContracts`.
`PlanV2StateCodec.read` must return a fresh initial state for null, blank, malformed, or non-object JSON.
Enum wire values are uppercase strings and are stable JSON values.

- [ ] **Step 4: Run codec tests**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: add story plan contracts`.

---

### Task 3: Scale and Scope Decision

**Files:**
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/StoryPlanningPrompts.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/plan/PlanJson.java`
- Create: `novelgen-service/src/main/java/com/zzdzz/novelgen/derive/service/DeriveStoryPlanService.java`
- Test: `novelgen-service/src/test/java/com/zzdzz/novelgen/derive/service/DeriveStoryScaleV2Test.java`

**Interfaces:**
- Consumes: `PlanContracts.ScopeContract`, `PlanV2StateCodec`
- Produces: `DeriveStoryPlanService.advance(long runId) -> PlanVO`
- Produces: `DeriveStoryPlanService.confirmScope(long runId, int volumeCount, boolean allowExpansion) -> PlanVO`
- Produces: package-private `static ScopeContract DeriveStoryPlanService.parseScale(JsonNode, RunDO)`
- Produces: package-private `static String DeriveStoryPlanService.buildScaleUser(RunDO, int beatCount)`

- [ ] **Step 1: Write failing scale tests**

```java
@Test
void explicitFourVolumesCannotCollapse() throws Exception {
    RunDO run = runWithIntent("轻小说。第一卷：转学。第二卷：A高光。第三卷：B高光。第四卷：C高光。");
    ScopeContract scope = DeriveStoryPlanService.parseScale(json("""
        {"storyScale":"LONG","volumeCount":4,"chapterCount":48,"chapterAvg":2800,
         "scopeDecision":"MULTI_VOLUME","scopeEvidence":["第二卷：A高光"]}
        """), run);
    assertEquals(4, scope.volumeCount());
    assertEquals(48, scope.chapterCount());
}

@Test
void vagueOneVolumeIdeaDefaultsToOneVolume() throws Exception {
    RunDO run = runWithIntent("孤岛灯塔管理员失踪，两天内查明真相。");
    ScopeContract scope = DeriveStoryPlanService.parseScale(json("""
        {"storyScale":"SHORT","volumeCount":1,"chapterCount":10,"chapterAvg":2400,
         "scopeDecision":"SINGLE_VOLUME","scopeEvidence":["单一封闭案件"]}
        """), run);
    assertEquals(1, scope.volumeCount());
}

@Test
void ambiguousScopeReturnsNeedScopeWithoutCreatingBookRows() {
    PlanVO vo = service.advance(ambiguousRunId);
    assertEquals("NEED_SCOPE", vo.unit());
    assertNull(runMapper.selectById(ambiguousRunId).getNovelId());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveStoryScaleV2Test" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL because `DeriveStoryPlanService` and parser do not exist.

- [ ] **Step 3: Implement SCALE**

`buildScaleUser` sends the exact fields listed in spec §6.1.
`parseScale` rejects explicit Arabic or Chinese volume markers matching `第[\d一二三四五六七八九十]+卷`
unless the returned `volumeCount` equals the largest explicit volume number.
When no explicit count exists and `scopeDecision=NEED_CONFIRMATION`, return a `NEED_SCOPE` PlanVO without creating novel/chapter rows.
`confirmScope` writes the chosen count and expansion permission into `PLAN_V2.scope`, then continues to `SPINE`.

- [ ] **Step 4: Verify scale tests and existing plan tests**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveStoryScaleV2Test,DerivePlanWindowTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: add story scope planning`.

---

### Task 4: Story Spine and Thread Ledger

**Files:**
- Modify: `DeriveStoryPlanService`
- Use: `StoryThreadMapper`
- Test: `DeriveStorySpineTest`

**Interfaces:**
- Consumes: `ScopeContract`
- Produces: package-private `static StorySpine DeriveStoryPlanService.parseSpine(JsonNode, ScopeContract)`
- Produces: `persistSpine(long runId, long novelId, StorySpine) -> void`

- [ ] **Step 1: Write failing spine tests**

```java
@Test
void spineRequiresOneTruthStepPerVolumeAndPayoffForEveryGlobalThread() throws Exception {
    ScopeContract scope = scope(4, 48);
    StorySpine spine = DeriveStoryPlanService.parseSpine(json(validSpineJson()), scope);
    assertEquals(4, spine.truthLadder().size());
    assertThrows(PlanJson.Bad.class,
            () -> DeriveStoryPlanService.parseSpine(json(missingVolumeFourJson()), scope));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveStorySpineTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement SPINE**

The prompt contains `mainMystery/finalTruth/finalChoice/truthLadder/globalThreads`.
Persist every global CLUE/OBJECT thread with `status=OPEN` through `StoryThreadMapper.insertFull`.

- [ ] **Step 4: Run spine tests**

Run Step 2 command. Expected: PASS; duplicate volume numbers and missing payoff fail.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: add story spine and thread ledger`.

---

### Task 5: Volume and Character Contracts

**Files:**
- Modify: `DeriveStoryPlanService`
- Modify: `CharacterPlanMapper` usage
- Modify: `VolumePlanMapper` usage
- Test: `DeriveVolumeCharacterPlanTest`

**Interfaces:**
- Consumes: `ScopeContract`, `StorySpine`
- Produces: package-private `static List<VolumeContract> DeriveStoryPlanService.parseVolumes(JsonNode, ScopeContract)`
- Produces: package-private `static List<CharacterContract> DeriveStoryPlanService.parseCharacters(JsonNode, ScopeContract)`
- Produces: `persistVolumes(...) -> void`
- Produces: `persistCharacters(...) -> void`

- [ ] **Step 1: Write failing tests**

```java
@Test
void fourVolumesMustCoverFortyEightChaptersWithCharacterSpotlights() throws Exception {
    List<VolumeContract> volumes = DeriveStoryPlanService.parseVolumes(
            json(fourVolumesJson()), scope(4, 48));
    List<CharacterContract> chars = DeriveStoryPlanService.parseCharacters(
            json(threeHighlightCharactersJson()), scope(4, 48));
    assertEquals(List.of(1, 13, 25, 37),
            volumes.stream().map(VolumeContract::fromChapter).toList());
    assertEquals(List.of(2, 3, 4),
            chars.stream().map(CharacterContract::spotlightVolume).toList());
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveVolumeCharacterPlanTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement VOLUMES and CHARACTERS**

Volume chapter ranges must be continuous, non-overlapping, and cover `1..chapterCount`.
Every non-final volume requires a non-blank `nextHook`; the final volume requires empty `nextHook`.
Character keys must be unique `C1..Cn`; every spotlight volume must reference an existing volume.

- [ ] **Step 4: Run tests**

Run Step 2 command. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: add volume and character contracts`.

---

### Task 6: Chapter Slots and Beat Routing

**Files:**
- Create: `DeriveBeatRouteService`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/BeatRouteVO.java`
- Modify: `chapters.plan_contract` persistence
- Modify: `beat_instance` route persistence
- Test: `DeriveBeatRouteServiceTest`

**Interfaces:**
- Consumes: `VolumeContract`, `CharacterContract`, existing `InvBeat` inventory and `LabelIndexDO`
- Produces: internal `RouteContext(List<InvBeat>, VolumeContract, List<CharacterContract>, Set<String>, int, int)`
- Produces: internal `RoutePlan(int volumeNo, List<ChapterSlotContract>, List<BeatAssignment>, List<SkipAssignment>)`
- Produces: `DeriveBeatRouteService.routeVolume(long runId, int volumeNo) -> BeatRouteVO`
- Produces: package-private `static RoutePlan DeriveBeatRouteService.parseRoute(JsonNode, RouteContext)`
- Produces: package-private `static List<PlanIssue> DeriveBeatRouteService.validateRoute(RoutePlan, RouteContext)`

- [ ] **Step 1: Write failing routing tests**

```java
@Test
void routingUsesEachBeatOnceAndMarksUnusedBeatWithReason() throws Exception {
    RoutePlan plan = DeriveBeatRouteService.parseRoute(json(validRouteJson()), context());
    assertTrue(validateRoute(plan, context()).isEmpty());
    assertEquals(Set.of(31, 32), assignedSeqs(plan));
}

@Test
void duplicateBeatOrUnknownThreadFails() throws Exception {
    assertThrows(PlanJson.Bad.class,
            () -> DeriveBeatRouteService.parseRoute(json(duplicateSeqJson()), context()));
    assertThrows(PlanJson.Bad.class,
            () -> DeriveBeatRouteService.parseRoute(json(unknownThreadJson()), context()));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveBeatRouteServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement deterministic slot template and route parsing**

Generate the 12 chapter slots from spec §9.2.
Use existing beats first; create `SYNTHETIC` beats only for uncovered required slots; mark extra beats skipped with non-blank reasons.
Route persistence uses a single transaction per volume and must be idempotent by deleting only that volume before reinsert.

- [ ] **Step 4: Add idempotency test and run**

Add `replanSameVolumeDoesNotDuplicateRowsAndDoesNotTouchOtherVolume` to the same test file, then run Step 2. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: add semantic beat routing`.

---

### Task 7: Plan Validation and State Simulation

**Files:**
- Create: `DerivePlanValidationService`
- Create: `novelgen-model/src/main/java/com/zzdzz/novelgen/model/vo/derive/PlanValidationVO.java`
- Modify: `DeriveWriteService`
- Test: `DerivePlanValidationServiceTest`
- Test: `DeriveWorldStateInjectTest`

**Interfaces:**
- Consumes: character plans, threads, chapter contracts, beat routes, `DRAFT.changes`, `world_states`
- Produces: `validateBeatRoute(long runId, int volumeNo) -> PlanValidationVO`
- Produces: `validateVolume(long runId, int volumeNo) -> PlanValidationVO`
- Produces: `validateBook(long runId) -> PlanValidationVO`
- Produces: `DeriveWriteService.WORLD_STATE_PROMPT` emits `characters` while `world_states` read paths accept legacy `statuses`

- [ ] **Step 1: Write failing validation tests**

```java
@Test
void deadCharacterCannotReturnAsLiveButMemoryAppearanceIsAllowed() {
    var dead = character("C2", false, "DEAD");
    assertIssue("CHARACTER_NOT_ALIVE", validate(route(18, "C2", "LIVE"), dead));
    assertTrue(validate(route(18, "C2", "MEMORY"), dead).isEmpty());
}

@Test
void finalBookRejectsOpenRequiredThreadAndMissingNonFinalHook() {
    assertIssue("REQUIRED_THREAD_OPEN", validateBookWithOpenThread());
    assertIssue("NEXT_HOOK_MISSING", validateVolumeWithoutHook(1));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DerivePlanValidationServiceTest,DeriveWorldStateInjectTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement pure validators**

Implement state, knowledge, appearance, character function, thread lifecycle, volume closure, and final-book checks from spec §10.
Update the `world_state` extraction contract to emit stable-key `characters` snapshots without removing legacy `statuses` reads.
Hard errors return issue codes; soft warnings use `WARN` and do not throw.

- [ ] **Step 4: Add missing-knowledge test and run**

Add `characterCannotUseKnowledgeBeforeLearningIt`, then run Step 2. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: validate story plans and state transitions`.

---

### Task 8: PLAN_V2 Orchestration in DerivePlanService

**Files:**
- Modify: `DerivePlanService`
- Modify: `PlanVO`
- Modify: existing `DerivePlanWindowTest`
- Test: `DerivePlanOrchestrationV2Test`

**Interfaces:**
- Consumes: `DeriveStoryPlanService.advance(long)`
- Consumes: `DeriveBeatRouteService.routeVolume(long, int)`
- Consumes: `DerivePlanValidationService.validateBook(long)`
- Produces: `DerivePlanService.plan(long) -> PlanVO`
- Produces: `PlanVO.unit()`、`volumeNo()`、`routedBeats()`、`validationWarnings()`、`allDone()`

- [ ] **Step 1: Write failing orchestration tests**

```java
@Test
void newRunWalksScaleSpineVolumesCharactersRoutesThenValidate() {
    when(storyPlan.advance(9L)).thenReturn(unit("SPINE"));
    when(route.routeVolume(9L, 1)).thenReturn(routeVO(1));
    PlanVO vo = service.plan(9L);
    assertEquals("SPINE", vo.unit());
}

@Test
void legacyRunWithStepsPlanDoesNotEnterPlanV2() {
    PlanVO vo = service.plan(10L);
    assertEquals("LEGACY", vo.unit());
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DerivePlanOrchestrationV2Test,DerivePlanWindowTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement the state machine**

Choose V2 only when `steps.PLAN_V2` exists or the run has neither `steps.PLAN` nor a planned novel.
Preserve all existing legacy methods for historical runs.
Run validation after each routed volume; run book validation before `DONE`.

- [ ] **Step 4: Verify idempotent retry**

Add `retrySameUnitDoesNotAdvanceOrDuplicateRows`; run Step 2. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: orchestrate plan v2`.

---

### Task 9: Draft Consumption and Character-Key Binding

**Files:**
- Modify: `DeriveDraftService`
- Test: `DeriveDraftPlanConsumptionTest`

- [ ] **Step 1: Write failing draft integration tests**

```java
@Test
void batchInputContainsVolumeContractChapterSlotAndBoundCharacterNames() {
    String user = service.buildBatchUser(run, novel, chapters, beats);
    assertTrue(user.contains("VC2-LOCAL-TRUTH"));
    assertTrue(user.contains("\"plan_contract\""));
    assertTrue(user.contains("\"displayName\":\"林雾\""));
}

@Test
void batchParserCannotMoveBeatToAnotherChapter() {
    assertThrows(PlanJson.Bad.class,
            () -> service.parseBatch(json(movedSeqJson()), context()));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveDraftPlanConsumptionTest,DeriveDraftBatchTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Bind character keys and inject planning context**

After HEAD A creates cast names, persist a `character_key -> name` mapping in `story_assets` and resolve plan focus keys for all following ⑥b calls.
Add volume contract, chapter `plan_contract`, current character states and thread instructions to `buildBatchUser`.

- [ ] **Step 4: Run existing draft tests**

Run Step 2. Expected: PASS; old fixtures without character keys remain readable.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: consume story contracts in draft`.

---

### Task 10: P6 and QC Story-Plan Checks

**Files:**
- Modify: `DeriveGateService`
- Modify: `DeriveQcService`
- Modify: `DeriveAutoFlowService`
- Modify: `GateVO`
- Modify: `QcVO`
- Test: `DeriveGateQcStoryPlanTest`

- [ ] **Step 1: Write failing gate/QC tests**

```java
@Test
void gateInputContainsVolumeClosureAndCharacterHighlightContracts() {
    assertTrue(String.valueOf(gateService.buildUser(run, novel, chapters, beats, motifs))
            .contains("\"highlightChoice\""));
}

@Test
void qcAddsOpenThreadAndFunctionConflictIssues() {
    PlanValidationVO plan = planWithOpenThreadAndUnresolvedConflict();
    assertTrue(qcIssues(run, plan).contains("REQUIRED_THREAD_OPEN"));
}

@Test
void reworkCarriesPlanRepairTargetAndAutoFlowDispatchesIt() {
    assertIssueTarget("PLAN_VOLUME:2", gateWithRouteIssue().repairTarget());
    assertIssueTarget("PLAN_VOLUME:2", qcWithRouteIssue().repairTarget());
    assertEquals("PLAN_VOLUME:2", autoFlow.nextReworkTarget(runWithRouteIssue()));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveGateQcStoryPlanTest,DeriveGateServiceTest,DeriveQcDirectSkipLlmTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement checks**

P6 receives volume contracts, character contracts and thread summaries.
QC appends hard plan issue messages before LLM quick checks and preserves the existing direct-mode script-only behavior.
Persist `repairTarget=DRAFT|PLAN_VOLUME:<n>|SPINE|SCALE` in `steps.GATE` or `steps.QC_REPAIR_TARGET`.
On `REWORK`, `DeriveAutoFlowService` uses the target: draft issues run the existing draft reset,
volume issues call `DeriveBeatRouteService.replanVolume`, and spine/scale issues stop for explicit plan reset.

- [ ] **Step 4: Run tests**

Run Step 2. Expected: PASS.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: enforce story contracts in gate and qc`.

---

### Task 11: Query, API and Run-Detail UI

**Files:**
- Modify: `DeriveQueryService`
- Modify: `DeriveResetService`
- Modify: `DeriveRunController`
- Create: `PlanScopeConfirmDTO`
- Create: `StoryPlanDetailVO`
- Modify: `DeriveRunDetailView.vue`
- Modify: `deriveLabels.js`
- Test: `DeriveStoryPlanQueryTest`、existing `DeriveQueryServiceTest`

- [ ] **Step 1: Write failing query tests**

```java
@Test
void storyPlanDetailReturnsFourVolumesCharactersThreadsAndSlots() {
    StoryPlanDetailVO detail = service.storyPlan(runId);
    assertEquals(4, detail.volumes().size());
    assertEquals(3, detail.highlightCharacters().size());
    assertEquals(48, detail.chapterSlots().size());
}

@Test
void clearPlanningDeletesPlanV2RowsAndContractsButKeepsSampleData() {
    resetService.clearPlanning(runId);
    assertEquals(0, characterPlanMapper.selectCount(null));
    assertEquals(0, storyThreadMapper.selectCount(null));
}
```

- [ ] **Step 2: Run and verify FAIL**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveStoryPlanQueryTest,DeriveQueryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: Implement read model and endpoints**

Add:

```text
POST /api/derive/runs/{id}/plan/confirm-scope
GET  /api/derive/runs/{id}/story-plan
GET  /api/derive/runs/{id}/volumes/{volumeNo}/beats
POST /api/derive/runs/{id}/volumes/{volumeNo}/replan
```

Controller only validates DTO and calls services.
`clearPlanning` must delete V121 rows and clear new contract columns while preserving ⑤ sample fields.

- [ ] **Step 4: Implement UI cards and run frontend build**

Add cards for scope, spine, volumes, characters, threads, slots, validation and replan.
Run:

```powershell
cd web
npm run build
```

Expected: build success.

- [ ] **Step 5: Verification checkpoint**

Suggested commit message: `feat: expose story plan read model`.

---

### Task 12: End-to-End Fixture and Acceptance

**Files:**
- Modify: `docs/STATUS.md`
- Test fixture: `novelgen-service/src/test/resources/derive/story_plan_four_volumes_12.json`
- Test fixture: `novelgen-service/src/test/resources/derive/story_plan_four_volumes_12_invalid.json`

- [ ] **Step 1: Add the four-volume fixture**

The valid fixture must contain:

```text
4 volumes
12 chapters per volume
1 main mystery
1 local case per volume
characters A/B/C with spotlight volumes 2/3/4
one dead-character memory appearance
all required threads closed in volume 4
```

The invalid fixture is identical except C2 uses a clue before learning it. It must return
`KNOWLEDGE_NOT_ACQUIRED` and must not be repaired by the validator.

- [ ] **Step 2: Write failing fixture acceptance test**

```java
@Test
void fourVolumeFixturePassesBookValidationAfterFixingPlannedDefects() {
    assertTrue(DerivePlanValidationService.validateBook(fixtureRun()).hardErrors().isEmpty());
}

@Test
void invalidFixtureRejectsKnowledgeBeforeLearning() {
    assertIssue("KNOWLEDGE_NOT_ACQUIRED",
            DerivePlanValidationService.validateBook(invalidFixtureRun()));
}
```

Run:

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test -pl novelgen-service -am "-Dtest=DeriveStoryPlanValidationFixtureTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL until the fixture and final read path are complete.

- [ ] **Step 3: Run the complete backend suite**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q test
```

Expected: BUILD SUCCESS, zero failures.

- [ ] **Step 4: Run build-only and local smoke checks**

```powershell
$env:JAVA_HOME='D:\Program\Java\jdk-21'; mvn -q compile
cd web
npm run build
```

Expected: compile and frontend build success.
If local PostgreSQL is available, start the derive app once and confirm Flyway applies V121 successfully;
record the result without changing any online service.
Local HTTP smoke is allowed; deployment and long real-LLM run require separate user authorization.

- [ ] **Step 5: Update STATUS and report checkpoint**

Append implementation evidence, test counts, migration status, remaining risks and suggested next step to `docs/STATUS.md`.
Do not commit. Suggested commit message: `feat: implement story planning and beat routing`.

---

## Self-Review

### Spec Coverage

| Spec section | Plan task |
|---|---|
| 5.1–5.6 数据模型 | Task 1、Task 2、Task 7 |
| 6.1 SCALE/scope | Task 3 |
| 6.2 SPINE | Task 4 |
| 6.3–6.4 VOLUMES/CHARACTERS | Task 5 |
| 6.5 SLOT_ROUTE | Task 6 |
| 7 业务模块 | Tasks 3–9 |
| 8 工作流 | Task 8、Task 9 |
| 9 四卷12章模板 | Task 5、Task 6、Task 12 |
| 10 硬/软校验 | Task 7、Task 10 |
| 11 API/前端 | Task 11 |
| 12 幂等/错误处理 | Task 6、Task 8、Task 11 |
| 13 迁移兼容 | Task 1、Task 8 |
| 14 测试验收 | Tasks 1–12 |
| 15 分阶段落地 | Task 12 and execution phase boundary |

### Type Consistency

- All plan units use `PlanContracts.PlanUnit`.
- All state transitions use `ScopeContract` and `PlanV2State`.
- Character references use stable `character_key`; names are bound only in ⑥b.
- Thread references use `thread_key` from `derive_story_thread`.
- Route persistence uses `RoutePlan` and `BeatRouteVO`; no alternate DTO names.
- Validation returns `PlanValidationVO` with `PlanIssue` entries.

### Execution Boundary

Task 1–10 produce the backend structural planning system.
Task 11 exposes it to the UI.
Task 12 proves the complete four-volume fixture and records evidence.
Each task leaves the repository compilable and testable.
