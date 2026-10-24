package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 章注机器合成：validate 不再卡「缺章注」；buildComment 产出必须能被
 * COMMENT_PATTERN（⑧ checkBeats 对账用）解析回同一组 seq——这是合成章注与⑧的契约。
 */
class DeriveCommentSynthTest {

    private static BeatInstanceDO beat(int seq) {
        BeatInstanceDO b = new BeatInstanceDO();
        b.setSeq(seq);
        return b;
    }

    @Test
    void validate_passesWithoutComment() {
        // 纯正文、无章注：P0 后不再打回（旧口径在此必 fail）
        String body = "陆青走在楼道里。" + "正文内容".repeat(50);
        assertNull(DeriveWriteService.validate(body, 100, 300, "陆青", true));
        assertTrue(DeriveWriteService.validate(body, 100, 300, "不存在的主角", true)
                .contains("主角名"));
    }

    @Test
    void buildComment_roundTripsThroughPattern() {
        String body = "正文".repeat(100);
        String comment = DeriveWriteService.buildComment(body, List.of(beat(4), beat(5)));

        var m = DeriveWriteService.COMMENT_PATTERN.matcher(comment);
        assertTrue(m.find(), "合成章注必须匹配 COMMENT_PATTERN：" + comment);
        assertEquals(String.valueOf(DeriveWriteService.len(body)), m.group(1));
        assertEquals("4,5", m.group(2).trim(), "⑧ 按 [,，\\s]+ 切分，容忍尾随空格");
    }

    @Test
    void modelCommentIsStrippedThenRebuilt() {
        // LLM 带了（可能错的）章注：剥掉后按系统实数重拼
        String raw = "正文开头。\n正文内容\n<!-- 本章999字，挂拍：99 -->";
        String body = DeriveWriteService.extractContent(raw);
        assertFalse(body.contains("章注") || body.contains("999字"), "模型章注应被剥离");
        String comment = DeriveWriteService.buildComment(body, List.of(beat(4), beat(5)));
        var m = DeriveWriteService.COMMENT_PATTERN.matcher(comment);
        assertTrue(m.find());
        assertEquals(String.valueOf(body.replaceAll("\\s", "").length()),
                m.group(1), "字数以系统实数为准，不信模型自报");
        assertEquals("4,5", m.group(2).trim(), "seq 以计划拍为准，不信模型自报");
    }
}
