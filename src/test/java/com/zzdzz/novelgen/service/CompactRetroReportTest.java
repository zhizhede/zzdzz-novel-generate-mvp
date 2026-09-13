package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复盘报告紧凑化：总评/drifts/下卷建议的裁剪与格式。 */
class CompactRetroReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compactsReviewSection() throws Exception {
        String report = """
                {"vol_no":5,"mechanical":{"status_count":{}},
                 "review":{"overall":"drift","summary":"总评一段话",
                   "drifts":[{"type":"foreshadow","severity":"major","where":"全卷",
                              "issue":"F18 悬置未兑现","suggestion":"下卷开篇兑现"}],
                   "highlights":["亮点"],"next_volume":"先回收 F18 再进主线"}}
                }""".strip();
        String out = ContextPackerService.compactRetroReport(report, mapper);
        assertTrue(out.contains("总评（drift）：总评一段话"));
        assertTrue(out.contains("[major|foreshadow] 全卷：F18 悬置未兑现 → 下卷开篇兑现"));
        assertTrue(out.contains("下卷建议：先回收 F18 再进主线"));
    }

    @Test
    void capsDriftsAtFourAndClipsLongText() throws Exception {
        StringBuilder drifts = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) drifts.append(',');
            drifts.append("{\"type\":\"pacing\",\"severity\":\"minor\",\"where\":\"第").append(i)
                    .append("章\",\"issue\":\"").append("很".repeat(200)).append("\",\"suggestion\":\"改\"}");
        }
        String report = "{\"review\":{\"overall\":\"drift\",\"summary\":\"s\",\"drifts\":[" + drifts + "]}}";
        String out = ContextPackerService.compactRetroReport(report, mapper);
        assertEquals(4, out.split("\\[minor\\|pacing\\]").length - 1);
        assertTrue(out.length() <= 1200);
    }

    @Test
    void badJsonReturnsEmpty() {
        assertEquals("", ContextPackerService.compactRetroReport("not-json", mapper));
    }
}
