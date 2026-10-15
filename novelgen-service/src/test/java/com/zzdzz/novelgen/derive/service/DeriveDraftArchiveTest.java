package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.DraftHistoryMapper;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DraftHistoryDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * V113 打回归档：clearForRewrite 前 content 非空的章逐行落 draft_history——
 * 空正文跳过、version=qc_round（下限 1）、字段齐全（run11 清稿丢稿教训的回归钉）。
 */
class DeriveDraftArchiveTest {

    private final DraftHistoryMapper mapper = mock(DraftHistoryMapper.class);
    private final DeriveDraftService svc = new DeriveDraftService(
            null, null, null, null, null, null, null, mapper, null);

    private static ChapterDO ch(long id, int no, String content) {
        ChapterDO c = new ChapterDO();
        c.setId(id);
        c.setChapterNo(no);
        c.setContent(content);
        c.setCharCount(content == null ? null : content.length());
        return c;
    }

    private static RunDO run(Long id, Integer qcRound) {
        RunDO r = new RunDO();
        r.setId(id);
        r.setQcRound(qcRound);
        return r;
    }

    @Test
    void archivesOnlyNonBlankChapters() {
        svc.archiveRejectedDrafts(run(11L, 2), 20L,
                List.of(ch(101L, 1, "第一轮正文一"), ch(102L, 2, "第一轮正文二"),
                        ch(103L, 3, null), ch(104L, 4, "  ")));

        ArgumentCaptor<DraftHistoryDO> cap = ArgumentCaptor.forClass(DraftHistoryDO.class);
        verify(mapper, times(2)).insert(cap.capture());
        List<DraftHistoryDO> rows = cap.getAllValues();
        assertEquals(101L, rows.get(0).getChapterId());
        assertEquals("第一轮正文一", rows.get(0).getContent());
        assertEquals(11L, rows.get(0).getRunId());
        assertEquals(20L, rows.get(0).getNovelId());
        assertEquals(2, rows.get(0).getVersion(), "version=归档时 qc_round");
        assertEquals(2, rows.get(0).getQcRound());
        assertEquals("REWORK", rows.get(0).getReason());
        assertEquals(6, rows.get(0).getCharCount(), "「第一轮正文一」=6字");
        assertEquals(102L, rows.get(1).getChapterId(), "空正文两章跳过，第二行=第二章");
        assertEquals(2, rows.get(1).getChapterNo());
    }

    @Test
    void qcRoundNull_versionFloorOne() {
        svc.archiveRejectedDrafts(run(10L, null), 21L, List.of(ch(201L, 1, "正文")));

        ArgumentCaptor<DraftHistoryDO> cap = ArgumentCaptor.forClass(DraftHistoryDO.class);
        verify(mapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getVersion(), "qc_round 空时 version 下限 1");
        assertEquals(0, cap.getValue().getQcRound());
    }

    @Test
    void firstDraft_noContent_zeroRows() {
        svc.archiveRejectedDrafts(run(10L, 0), 21L,
                List.of(ch(301L, 1, null), ch(302L, 2, null)));
        verify(mapper, times(0)).insert(org.mockito.ArgumentMatchers.any(DraftHistoryDO.class));
    }
}
