package com.zzdzz.novelgen.derive.draft;

import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStageRunMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.plan.compiler.CompiledStoryPlan;
import com.zzdzz.novelgen.derive.plan.compiler.PlanSnapshotService;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinalAuditServiceTest {

    @Mock
    private PlanSnapshotService snapshotService;
    @Mock
    private RunMapper runMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private DeriveStageRunMapper stageMapper;

    private CompiledStoryPlan plan;
    private FinalAuditService service;

    @BeforeEach
    void setUp() {
        plan = DraftFixtures.plan(59L, 3);
        DerivePlanSnapshotDO snapshot = new DerivePlanSnapshotDO();
        snapshot.setRevision(5);
        snapshot.setStatus("ADMITTED");
        snapshot.setPlanHash(plan.canonicalHash());
        snapshot.setGraph(plan.canonicalJson());
        lenient().when(snapshotService.requireActive(59L)).thenReturn(snapshot);
        lenient().when(snapshotService.loadCompiled(59L, 5)).thenReturn(plan);
        RunDO run = new RunDO();
        run.setId(59L);
        run.setNovelId(12L);
        run.setActivePlanRevision(5);
        lenient().when(runMapper.selectById(59L)).thenReturn(run);
        service = new FinalAuditService(snapshotService, runMapper, chapterMapper, stageMapper);
    }

    @Test
    void allNonemptyRevisionsProducePassWithoutPlanWrites() {
        when(chapterMapper.selectList(any())).thenReturn(chapters());

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.PASS, report.quality());
        assertEquals(5, report.planRevision());
        verify(runMapper, never()).updateStatus(anyLong(), any(), any());
        verify(chapterMapper, never()).updateContentIfRevision(
                anyLong(), anyInt(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void missingChapterProducesFailedQualityAndBlockerIssue() {
        when(chapterMapper.selectList(any())).thenReturn(chapters("missing:2"));

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.FAILED, report.quality());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "FINAL_AUDIT_BLOCKER".equals(issue.code())));
    }

    @Test
    void emptyChapterProducesFailedQualityAndBlockerIssue() {
        when(chapterMapper.selectList(any())).thenReturn(chapters("empty:2"));

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.FAILED, report.quality());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "FINAL_AUDIT_EMPTY_CHAPTER".equals(issue.code())));
    }

    @Test
    void metaLeakProducesFailedQualityAndBlockerIssue() {
        when(chapterMapper.selectList(any())).thenReturn(chapters("meta:3"));

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.FAILED, report.quality());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "FINAL_AUDIT_META_LEAK".equals(issue.code())));
    }

    @Test
    void advisoryParagraphProducesAdvisoryQuality() {
        when(chapterMapper.selectList(any())).thenReturn(chapters("repeat:2"));

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.ADVISORY, report.quality());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "FINAL_AUDIT_STYLE".equals(issue.code())));
    }

    @Test
    void activeRevisionFailureProducesFailedQuality() {
        when(snapshotService.requireActive(59L)).thenThrow(new RuntimeException("active revision unavailable"));

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.FAILED, report.quality());
        assertTrue(report.issues().stream()
                .anyMatch(issue -> "FINAL_AUDIT_BLOCKER".equals(issue.code())
                        && "run[59]".equals(issue.path())));
    }

    @Test
    void degradedDraftMarkerIsReported() {
        when(chapterMapper.selectList(any())).thenReturn(chapters());
        DeriveStageRunDO draft = new DeriveStageRunDO();
        draft.setStage("DRAFT");
        draft.setStatus("DONE");
        draft.setMessage("{\"chaptersDegraded\":1}");
        when(stageMapper.findLatest(59L, "DRAFT")).thenReturn(draft);

        FinalAuditReport report = service.audit(59L);

        assertEquals(FinalAuditReport.FinalQuality.DEGRADED, report.quality());
    }

    private static List<ChapterDO> chapters(String... mutations) {
        java.util.ArrayList<ChapterDO> rows = new java.util.ArrayList<>(List.of(
                chapter(1, DraftFixtures.prose(1, 80)),
                chapter(2, DraftFixtures.prose(2, 80)),
                chapter(3, DraftFixtures.prose(3, 80))));
        for (String mutation : mutations) {
            String[] parts = mutation.split(":", 2);
            int index = Integer.parseInt(parts[1]) - 1;
            if ("missing".equals(parts[0])) {
                rows.remove(index);
                continue;
            }
            ChapterDO row = rows.get(index);
            switch (parts[0]) {
                case "empty" -> row.setContent("");
                case "meta" -> row.setContent(row.getContent()
                        + "\n我先核对状态包，再决定是否输出本章正文。");
                case "repeat" -> row.setContent(row.getContent()
                        + "\n" + DraftFixtures.prose(1, 80) + "\n" + DraftFixtures.prose(1, 80));
                default -> throw new IllegalArgumentException(parts[0]);
            }
        }
        return rows;
    }

    private static ChapterDO chapter(int no, String content) {
        ChapterDO row = new ChapterDO();
        row.setNovelId(12L);
        row.setChapterNo(no);
        row.setPlanRevision(5);
        row.setContent(content);
        row.setStatus("DRAFTED");
        return row;
    }
}



