package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GateCheck 序列化形状基线：record 化后落库 JSON 必须与历史 Map 构造逐键一致
 * （check/value/baseline/abs_max/ok；abs_max 为 null 时不出现）——gate_reports 存量与档案页的兼容线。
 */
class GateCheckJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 复刻改造前 check() 助手的 Map 构造。 */
    private static Map<String, Object> legacyCheck(String name, Object value, Object expect, Object absMax, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("value", value);
        m.put("baseline", expect);
        if (absMax != null) m.put("abs_max", absMax);
        m.put("ok", ok);
        return m;
    }

    @Test
    void shapeMatchesLegacyMapWithAbsMax() throws Exception {
        GateService.GateCheck c = new GateService.GateCheck("simile_per1k", 8.5, 2.4, 8.0, false);
        String recordJson = mapper.writeValueAsString(List.of(c));
        String legacyJson = mapper.writeValueAsString(List.of(legacyCheck("simile_per1k", 8.5, 2.4, 8.0, false)));
        assertThat(recordJson).isEqualTo(legacyJson);
    }

    @Test
    void shapeMatchesLegacyMapWithoutAbsMax() throws Exception {
        GateService.GateCheck c = new GateService.GateCheck("chapter_length", 2100, 1530, null, true);
        String recordJson = mapper.writeValueAsString(List.of(c));
        String legacyJson = mapper.writeValueAsString(List.of(legacyCheck("chapter_length", 2100, 1530, null, true)));
        assertThat(recordJson).isEqualTo(legacyJson);
        assertThat(recordJson).doesNotContain("abs_max");
    }

    @Test
    void verdictFiltersFailedChecks() {
        GateService.GateVerdict v = new GateService.GateVerdict(false, List.of(
                new GateService.GateCheck("a", 1, null, null, true),
                new GateService.GateCheck("b", 2, null, 3, false)));
        assertThat(v.passed()).isFalse();
        assertThat(v.failedChecks()).extracting(GateService.GateCheck::check).containsExactly("b");
    }
}
