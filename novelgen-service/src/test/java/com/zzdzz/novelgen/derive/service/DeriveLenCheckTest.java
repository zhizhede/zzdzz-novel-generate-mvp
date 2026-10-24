package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.AssetOutlineMapper;
import com.zzdzz.novelgen.derive.mapper.BeatInstanceMapper;
import com.zzdzz.novelgen.derive.mapper.CalibrationMapper;
import com.zzdzz.novelgen.derive.mapper.ChapterMapper;
import com.zzdzz.novelgen.derive.mapper.LabelIndexMapper;
import com.zzdzz.novelgen.derive.mapper.NovelMapper;
import com.zzdzz.novelgen.derive.mapper.QcReportMapper;
import com.zzdzz.novelgen.derive.mapper.RunMapper;
import com.zzdzz.novelgen.derive.mapper.WordlistMapper;
import com.zzdzz.novelgen.derive.mapper.WorkFingerprintMapper;
import com.zzdzz.novelgen.derive.mapper.WorldStateMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** V115 len_check：字数带校验可选、默认关——⑦ 跳过±10% gate（无修整轮），⑧「2每章字数」降报告。 */
class DeriveLenCheckTest {

    private final DeriveQcService qc = new DeriveQcService(
            mock(RunMapper.class), mock(NovelMapper.class), mock(ChapterMapper.class),
            mock(BeatInstanceMapper.class), mock(WordlistMapper.class), mock(QcReportMapper.class),
            mock(CalibrationMapper.class), mock(WorkFingerprintMapper.class),
            mock(AssetOutlineMapper.class), mock(LabelIndexMapper.class),
            mock(WorldStateMapper.class), mock(LlmJson.class));

    private static RunDO run(Boolean lenCheck) {
        RunDO r = new RunDO();
        r.setId(9L);
        r.setLenCheck(lenCheck);
        return r;
    }

    private static ChapterDO ch(int no, int budget, int chars) {
        ChapterDO c = new ChapterDO();
        c.setChapterNo(no);
        c.setBudget(budget);
        c.setCharCount(chars);
        return c;
    }

    @Test
    void writeGate_skipsLengthBandWhenOff() {
        String body = "陆青走在楼道里。" + "短".repeat(20); // ~28字，远低于预算带
        // lenCheck 开：出带必拦（"正文字数"前缀=修整轮路由键）
        String issue = DeriveWriteService.validate(body, 100, 300, "陆青", true);
        assertTrue(issue.startsWith("正文字数"));
        // lenCheck 关（默认）：出带放行
        assertNull(DeriveWriteService.validate(body, 100, 300, "陆青", false));
    }

    @Test
    void writeGate_otherChecksStillHardWhenOff() {
        String body = "陆青走在楼道里。".repeat(30);
        assertTrue(DeriveWriteService.validate(body, 10, 20, "不存在的主角", false)
                .contains("主角名"), "关字数带不关主角名 gate");
        assertTrue(DeriveWriteService.validate(body + "O-P1素材来源", 10, 500, "陆青", false)
                .contains("禁词"), "关字数带不关禁词 gate");
    }

    @Test
    void qcChapterBudget_degradesToReportWhenOff() {
        List<ChapterDO> chapters = List.of(ch(1, 2500, 1800), ch(2, 2500, 4000));
        var off = qc.checkChapterBudgets(run(null), chapters);
        assertTrue(off.pass(), "默认关=降报告不判负");
        assertTrue(off.detail().contains("len_check=关"));
        assertTrue(off.detail().contains("超差 2 章"), "读数仍记录");

        var on = qc.checkChapterBudgets(run(true), chapters);
        assertFalse(on.pass(), "开启=原口径硬判");
        assertTrue(on.detail().contains("超差"));
    }
}
