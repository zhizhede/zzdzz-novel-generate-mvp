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
import com.zzdzz.novelgen.model.entity.derive.WordlistDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 修3 类别浓度改全书级：Σ ≥ ceil(章数/2) 硬判、单章 0 命中仅记录——
 * 治 run6/10/11「每章≥3」三连误伤（run12 优化后仍 10/12 章不达标）。
 */
class DeriveQcConcentrationTest {

    private final WordlistMapper wordlistMapper = mock(WordlistMapper.class);
    private final DeriveQcService svc = new DeriveQcService(
            mock(RunMapper.class), mock(NovelMapper.class), mock(ChapterMapper.class),
            mock(BeatInstanceMapper.class), wordlistMapper, mock(QcReportMapper.class),
            mock(CalibrationMapper.class), mock(WorkFingerprintMapper.class),
            mock(AssetOutlineMapper.class), mock(LabelIndexMapper.class),
            mock(WorldStateMapper.class), mock(LlmJson.class));

    private static ChapterDO ch(int no, String content) {
        ChapterDO c = new ChapterDO();
        c.setChapterNo(no);
        c.setContent(content);
        return c;
    }

    private void withTerms(String... words) {
        List<WordlistDO> rows = new ArrayList<>();
        for (String w : words) {
            WordlistDO d = new WordlistDO();
            d.setKind("CATEGORY_TERM");
            d.setCategory("推理");
            d.setWord(w);
            rows.add(d);
        }
        when(wordlistMapper.selectList(any())).thenReturn(rows);
    }

    @Test
    void sparseHitsPassUnderBookLevelRule() {
        withTerms("警察", "线索", "证据");
        // run12 实测形态：命中集中少数章、多数章 0——旧口径 10/12 章 fail，新口径 Σ≥ceil(n/2) 过
        List<ChapterDO> chapters = List.of(
                ch(1, "他遛狗回家。"), ch(2, "门缝飘出异味。"),
                ch(3, "警察来问话，他答不知道。"), ch(4, "继续躺平。"),
                ch(5, "游戏到深夜。"), ch(6, "现场勘查后警方带走证物，线索指向邻居"));
        var item = invoke(chapters);
        assertTrue(item.pass(), "Σ=4 ≥ ceil(6/2)=3 应过：" + item.detail());
        assertTrue(item.detail().contains("0词章"), "0 词章要记录但不判负");
    }

    @Test
    void allZeroFails() {
        withTerms("警察", "线索");
        List<ChapterDO> chapters = List.of(ch(1, "日常一"), ch(2, "日常二"), ch(3, "日常三"));
        var item = invoke(chapters);
        assertFalse(item.pass(), "全书 Σ=0 必须拦（真跑题）：" + item.detail());
    }

    @Test
    void emptyWordlistDegradesToReport() {
        when(wordlistMapper.selectList(any())).thenReturn(List.of());
        var item = invoke(List.of(ch(1, "任意"), ch(2, "任意")));
        assertTrue(item.pass(), "词表空降级报告");
        assertTrue(item.detail().contains("降级"));
    }

    private DeriveQcService.CheckItem invoke(List<ChapterDO> chapters) {
        return svc.checkCategoryConcentration(null, chapters);
    }
}
