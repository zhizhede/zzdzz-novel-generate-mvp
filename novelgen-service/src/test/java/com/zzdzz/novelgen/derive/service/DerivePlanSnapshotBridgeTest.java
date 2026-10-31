package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.CharacterPlanMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionService;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionDecision;
import com.zzdzz.novelgen.derive.plan.audit.PlanAdmissionResult;
import com.zzdzz.novelgen.derive.plan.audit.PlanAuditor;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.derive.plan.compiler.StoryPlanCompiler;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DerivePlanSnapshotBridgeTest {

    @Mock private RunMapper runMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private BeatInstanceMapper beatInstanceMapper;
    @Mock private CharacterPlanMapper characterPlanMapper;
    @Mock private VolumePlanMapper volumePlanMapper;
    @Mock private DeriveContractService contractService;
    @Mock private DeriveChapterScheduleService scheduleService;
    @Mock private PlanAdmissionService admissionService;
    @Mock private PlanSnapshotService snapshotService;

    @Test
    void buildPlanPreservesOldNarrativeDetailsWhileCompilerOwnsStructure() {
        DerivePlanSnapshotBridge bridge = new DerivePlanSnapshotBridge(
                runMapper, chapterMapper, beatInstanceMapper, characterPlanMapper,
                volumePlanMapper, contractService, scheduleService,
                new StoryPlanCompiler(), admissionService, snapshotService);
        RunDO run = run(59L, 12L);
        StoryContract contract = contract(run.getId());
        List<ChapterScheduleEntry> schedule = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, true, List.of(), List.of("GM-CLUE-01")));
        var first = bridge.buildPlan(run, contract, schedule,
                List.of(chapter(1, 1200), chapter(2, 1200)),
                List.of(beat(run.getId(), 1, 1, "B1",
                        "旧计划目标：发现纸屑",
                        "{\"focusCharacters\":[\"C1\"],\"writesKnowledge\":[\"纸屑存在\"]}")),
                List.of(), List.of());

        assertEquals(1, first.beats().stream()
                .filter(beat -> "B1".equals(beat.beatKey()))
                .findFirst().orElseThrow().knowledgeEffects().size());
        assertTrue(first.beats().stream()
                .filter(beat -> "B1".equals(beat.beatKey()))
                .findFirst().orElseThrow().goal().contains("旧计划目标"));
        assertEquals(2, first.chapters().size());
        assertTrue(first.chapters().get(0).scenes().stream()
                .anyMatch(scene -> "B1".equals(scene.beatKey())));
        assertTrue(new PlanAuditor().audit(first, contract).admitted(),
                "兼容桥生成的计划必须能通过确定性准入审计");
        assertFalse(first.canonicalJson().isBlank());

        var second = bridge.buildPlan(run, contract, schedule,
                List.of(chapter(1, 1200), chapter(2, 1200)),
                List.of(beat(run.getId(), 1, 1, "B1",
                        "旧计划目标：发现纸屑",
                        "{\"focusCharacters\":[\"C1\"],\"writesKnowledge\":[\"纸屑存在\"]}")),
                List.of(), List.of());
        assertEquals(first.canonicalHash(), second.canonicalHash());
    }

    private static RunDO run(long runId, long novelId) {
        RunDO run = new RunDO();
        run.setId(runId);
        run.setNovelId(novelId);
        run.setStatus("DRAFTING");
        return run;
    }

    private static StoryContract contract(long runId) {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 2, 1200, List.of("fixture"));
        return new StoryContract(runId, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林遥", "detective", "女", "调查者"),
                        new StoryContract.CharacterSlot("C2", "周砺", "opposition", "男", "嫌疑人")),
                new StoryContract.Truth("C2", "周砺", "掩盖旧账", "伪造记录",
                        "周砺掩盖旧账", "交出证据", 2, "掩盖旧账", "伪造记录"),
                List.of(new StoryContract.Clue("GM-CLUE-01", "门缝纸屑", 1, 2, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of("不得出现第二起案件"),
                List.of(new StoryContract.VolumeTruth(1, "周砺在第二章承认旧账", List.of())),
                scope);
    }

    private static ChapterDO chapter(int no, int budget) {
        ChapterDO row = new ChapterDO();
        row.setNovelId(12L);
        row.setChapterNo(no);
        row.setVolumeNo(1);
        row.setTitle("第" + no + "章");
        row.setBudget(budget);
        row.setStatus("PLANNED");
        return row;
    }

    private static BeatInstanceDO beat(long runId, int seq, int chapterNo,
                                       String key, String goal, String routeJson) {
        BeatInstanceDO row = new BeatInstanceDO();
        row.setRunId(runId);
        row.setSeq(seq);
        row.setChapterNo(chapterNo);
        row.setVolumeNo(1);
        row.setBeatKey(key);
        row.setSource("ASSET");
        row.setBeatType("事件");
        row.setPacing(new BigDecimal("0.50"));
        row.setStoryLayer(PlanContracts.StoryLayer.LOCAL_CASE.name());
        row.setBeatRole(PlanContracts.BeatRole.HOOK.name());
        row.setFilledText(goal);
        row.setRouteContract(routeJson);
        row.setStatus("PLANNED");
        return row;
    }

    @Test
    void materializeAndAdmitPersistsDraftBeforeAdmission() {
        RunDO run = run(59L, 12L);
        StoryContract contract = contract(run.getId());
        List<ChapterScheduleEntry> schedule = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, true, List.of(), List.of("GM-CLUE-01")));
        List<ChapterDO> chapters = List.of(chapter(1, 1200), chapter(2, 1200));
        List<BeatInstanceDO> beats = List.of(beat(run.getId(), 1, 1, "B1",
                "旧计划目标：发现纸屑",
                "{\"focusCharacters\":[\"C1\"],\"writesKnowledge\":[\"纸屑存在\"]}"));
        when(runMapper.selectById(59L)).thenReturn(run);
        when(chapterMapper.selectList(any())).thenReturn(chapters);
        when(beatInstanceMapper.selectList(any())).thenReturn(beats);
        when(contractService.require(run)).thenReturn(contract);
        when(scheduleService.require(run)).thenReturn(schedule);
        when(characterPlanMapper.selectList(any())).thenReturn(List.of());
        when(volumePlanMapper.selectList(any())).thenReturn(List.of());
        var snapshot = new com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO();
        snapshot.setId(71L);
        snapshot.setRevision(7);
        when(snapshotService.persistDraft(org.mockito.ArgumentMatchers.eq(59L),
                org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);
        var admitted = new PlanAdmissionResult(59L, 7, PlanAdmissionDecision.ADMIT,
                new com.zzdzz.novelgen.derive.plan.audit.PlanAuditReport(List.of()));
        when(admissionService.admit(59L, 7)).thenReturn(admitted);

        DerivePlanSnapshotBridge bridge = bridge();
        PlanAdmissionResult result = bridge.materializeAndAdmit(59L);

        assertEquals(PlanAdmissionDecision.ADMIT, result.decision());
        var order = org.mockito.Mockito.inOrder(snapshotService, admissionService);
        order.verify(snapshotService).persistDraft(
                org.mockito.ArgumentMatchers.eq(59L),
                org.mockito.ArgumentMatchers.any());
        order.verify(admissionService).admit(59L, 7);
    }

    private DerivePlanSnapshotBridge bridge() {
        return new DerivePlanSnapshotBridge(
                runMapper, chapterMapper, beatInstanceMapper, characterPlanMapper,
                volumePlanMapper, contractService, scheduleService,
                new StoryPlanCompiler(), admissionService, snapshotService);
    }

    @Test
    void oldStateAndDependenciesSurviveCompilerOverlay() {
        RunDO run = run(59L, 12L);
        StoryContract contract = contract(run.getId());
        List<ChapterScheduleEntry> schedule = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, true, List.of(), List.of("GM-CLUE-01")));
        List<BeatInstanceDO> beats = List.of(
                beat(run.getId(), 1, 1, "B1", "旧目标一",
                        "{\"focusCharacters\":[\"C1\"],\"writesKnowledge\":[\"纸屑存在\"]}"),
                beat(run.getId(), 2, 1, "B2", "旧目标二",
                        "{\"focusCharacters\":[\"C2\"],\"dependsOn\":[1],"
                                + "\"changesState\":{\"C2\":{\"alive\":false}}}"));
        var plan = bridge().buildPlan(run, contract, schedule,
                List.of(chapter(1, 1200), chapter(2, 1200)), beats, List.of(), List.of());
        var b1 = plan.beats().stream().filter(beat -> "B1".equals(beat.beatKey()))
                .findFirst().orElseThrow();
        var b2 = plan.beats().stream().filter(beat -> "B2".equals(beat.beatKey()))
                .findFirst().orElseThrow();
        assertTrue(b2.dependsOn().contains(b1.seq()));
        assertTrue(b2.stateEffects().stream().anyMatch(effect ->
                "C2".equals(effect.characterKey())
                        && effect.kind() == PlanContracts.StateChangeKind.DEAD));
    }

    @Test
    void unmappableDependencyFailsClosedInsteadOfDroppingIt() {
        RunDO run = run(59L, 12L);
        StoryContract contract = contract(run.getId());
        List<ChapterScheduleEntry> schedule = List.of(
                new ChapterScheduleEntry(1, 1, false, List.of("GM-CLUE-01"), List.of()),
                new ChapterScheduleEntry(2, 1, true, List.of(), List.of("GM-CLUE-01")));
        BeatInstanceDO dependent = beat(run.getId(), 2, 1, "B2", "旧目标二",
                "{\"focusCharacters\":[\"C2\"],\"dependsOn\":[99]}");
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                com.zzdzz.novelgen.common.web.BizException.class,
                () -> bridge().buildPlan(run, contract, schedule,
                        List.of(chapter(1, 1200), chapter(2, 1200)),
                        List.of(dependent), List.of(), List.of()));
        org.junit.jupiter.api.Assertions.assertTrue(
                thrown.getMessage().contains("PLAN_DEPENDENCY_UNMAPPABLE"));
    }
}

