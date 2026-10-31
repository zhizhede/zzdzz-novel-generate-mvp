package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.genre.MysteryQcAuditor;
import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeriveQcObligationIntegrationTest {

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
    private QcReportMapper qcReportMapper;
    @Mock
    private CalibrationMapper calibrationMapper;
    @Mock
    private WorkFingerprintMapper fingerprintMapper;
    @Mock
    private AssetOutlineMapper outlineMapper;
    @Mock
    private LabelIndexMapper labelIndexMapper;
    @Mock
    private WorldStateMapper worldStateMapper;
    @Mock
    private com.zzdzz.novelgen.llm.LlmJson llmJson;
    @Mock
    private DeriveStoryObligationMapper obligationMapper;
    @Mock
    private DeriveStoryObligationEvidenceMapper evidenceMapper;
    @Mock
    private MysteryQcAuditor mysteryQcAuditor;

    @Test
    void qcDoesNotUseTargetKeyStringContainsAndReadsPayloadEvidence() throws Exception {
        RunDO run = new RunDO();
        run.setId(66L);
        run.setGenre("MYSTERY");
        DeriveStoryObligationDO obligation = new DeriveStoryObligationDO();
        obligation.setObligationKey("CH2:PAYOFF_THREAD:GM-CLUE-01");
        obligation.setChapterNo(2);
        obligation.setKind("PAYOFF_THREAD");
        obligation.setTargetKey("GM-CLUE-01");
        obligation.setRequired(true);
        obligation.setSeverity("HARD");
        obligation.setSourceContract("MYSTERY");
        obligation.setPayload("{\"clueKey\":\"GM-CLUE-01\",\"phase\":\"PAYOFF\"}");
        when(obligationMapper.selectByRun(66L)).thenReturn(List.of(obligation));

        DeriveStoryObligationEvidenceDO evidence = new DeriveStoryObligationEvidenceDO();
        evidence.setObligationKey("CH2:PAYOFF_THREAD:GM-CLUE-01");
        evidence.setChapterNo(2);
        evidence.setEvidenceKind("THREAD_EFFECT");
        evidence.setEventKey("EV1");
        evidence.setEntityKeys("[]");
        evidence.setThreadKeys("[\"GM-CLUE-01\"]");
        evidence.setSource("BATCH");
        evidence.setConfidence(1.0);
        when(evidenceMapper.selectByRun(66L)).thenReturn(List.of(evidence));
        when(mysteryQcAuditor.audit(any(), any(), any())).thenReturn(List.of());

        DeriveQcService service = service();
        ChapterDO chapter = new ChapterDO();
        chapter.setChapterNo(2);
        chapter.setContent("正文完全没有出现 GM-CLUE-01，但结构证据表明该线索已经兑现。");

        List<String> issues = service.genreQcIssues(run, novel(), List.of(chapter));

        ArgumentCaptor<StoryKernel> captor = ArgumentCaptor.forClass(StoryKernel.class);
        org.mockito.Mockito.verify(mysteryQcAuditor).audit(any(), captor.capture(), any());
        StoryKernel kernel = captor.getValue();
        assertEquals("PAYOFF", kernel.obligations().get(0).payload().get("phase"));
        assertTrue(kernel.evidenceIndex().hasEvidence("CH2:PAYOFF_THREAD:GM-CLUE-01"));
        assertFalse(issues.stream().anyMatch(issue -> issue.contains("GM-CLUE-01")));
        assertTrue(issues.isEmpty());
    }

    private DeriveQcService service() throws Exception {
        DeriveQcService service = new DeriveQcService(runMapper, novelMapper, chapterMapper,
                beatInstanceMapper, wordlistMapper, qcReportMapper, calibrationMapper,
                fingerprintMapper, outlineMapper, labelIndexMapper, worldStateMapper, llmJson);
        inject(service, "obligationMapper", obligationMapper);
        inject(service, "evidenceMapper", evidenceMapper);
        inject(service, "mysteryQcAuditor", mysteryQcAuditor);
        return service;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static NovelDO novel() {
        NovelDO novel = new NovelDO();
        novel.setId(66L);
        novel.setStoryAssets("{}");
        return novel;
    }
}
