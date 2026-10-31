package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.genre.MysteryGateAuditor;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.StoryThreadMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeriveGateObligationIntegrationTest {

    @Mock
    private RunMapper runMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private BeatInstanceMapper beatInstanceMapper;
    @Mock
    private WordlistMapper wordlistMapper;
    @Mock
    private DeriveTruthContractService truthContractService;
    @Mock
    private com.zzdzz.novelgen.llm.LlmJson llmJson;
    @Mock
    private DeriveStoryObligationMapper obligationMapper;
    @Mock
    private DeriveStoryObligationEvidenceMapper evidenceMapper;
    @Mock
    private StoryThreadMapper storyThreadMapper;
    @Mock
    private com.zzdzz.novelgen.derive.service.DeriveContractService contractService;
    @Mock
    private MysteryGateAuditor mysteryGateAuditor;

    @Test
    void gateReadsObligationPayloadAndEvidenceWithFullKernel() throws Exception {
        RunDO run = new RunDO();
        run.setId(65L);
        run.setGenre("MYSTERY");
        DeriveStoryObligationDO obligation = new DeriveStoryObligationDO();
        obligation.setObligationKey("CH2:PLANT_THREAD:GM-CLUE-01");
        obligation.setChapterNo(2);
        obligation.setKind("PLANT_THREAD");
        obligation.setTargetKey("GM-CLUE-01");
        obligation.setRequired(true);
        obligation.setSeverity("HARD");
        obligation.setSourceContract("MYSTERY");
        obligation.setPayload("{\"clueKey\":\"GM-CLUE-01\",\"phase\":\"PLANT\"}");
        when(obligationMapper.selectByRun(65L)).thenReturn(List.of(obligation));

        DeriveStoryObligationEvidenceDO evidence = new DeriveStoryObligationEvidenceDO();
        evidence.setObligationKey("CH2:PLANT_THREAD:GM-CLUE-01");
        evidence.setChapterNo(2);
        evidence.setEvidenceKind("BEAT");
        evidence.setEventKey("EV1");
        evidence.setBeatSeq(1);
        evidence.setEntityKeys("[\"C1\"]");
        evidence.setThreadKeys("[\"GM-CLUE-01\"]");
        evidence.setSource("BATCH");
        evidence.setConfidence(1.0);
        when(evidenceMapper.selectByRun(65L)).thenReturn(List.of(evidence));

        StoryThreadDO thread = new StoryThreadDO();
        thread.setThreadKey("GM-CLUE-01");
        thread.setKind("CLUE");
        thread.setTitle("纸屑");
        thread.setPlantChapter(2);
        thread.setStatus("OPEN");
        thread.setOwnerCharacterKey("C1");
        thread.setPayoffChapter(4);
        when(storyThreadMapper.selectList(any())).thenReturn(List.of(thread));
        when(contractService.require(run)).thenReturn(contract());
        when(mysteryGateAuditor.audit(any(), any())).thenReturn(List.of());

        DeriveGateService service = service();

        List<String> issues = service.genreIssues(run);

        ArgumentCaptor<StoryKernel> captor = ArgumentCaptor.forClass(StoryKernel.class);
        org.mockito.Mockito.verify(mysteryGateAuditor).audit(captor.capture(), any());
        StoryKernel kernel = captor.getValue();
        assertEquals("PLANT", kernel.obligations().get(0).payload().get("phase"));
        assertTrue(kernel.evidenceIndex().hasEvidence("CH2:PLANT_THREAD:GM-CLUE-01"));
        assertTrue(!kernel.entities().stream()
                .filter(entity -> "C1".equals(entity.key())).findFirst().orElseThrow()
                .name().isBlank());
        assertEquals("GM-CLUE-01", kernel.threads().get(0).threadKey());
        assertTrue(issues.isEmpty());
    }

    private DeriveGateService service() throws Exception {
        DeriveGateService service = new DeriveGateService(runMapper, novelMapper, chapterMapper,
                beatInstanceMapper, wordlistMapper, truthContractService, llmJson);
        inject(service, "obligationMapper", obligationMapper);
        inject(service, "evidenceMapper", evidenceMapper);
        inject(service, "storyThreadMapper", storyThreadMapper);
        inject(service, "contractService", contractService);
        inject(service, "mysteryGateAuditor", mysteryGateAuditor);
        return service;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static StoryContract contract() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 4, 2000, List.of("测试"));
        return new StoryContract(65L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林默", "DETECTIVE", "男", "值班员"),
                        new StoryContract.CharacterSlot("C2", "苏晚", "ACCESS", "女", "档案员"),
                        new StoryContract.CharacterSlot("C3", "陆敬", "OPPOSITION", "男", "仓管")),
                null, List.of(), List.of(), List.of(), List.of(), scope);
    }
}
