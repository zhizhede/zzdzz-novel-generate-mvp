package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** AI 估档（2026-10-17 用户定调：字数留空=AI 判篇幅与带宽，非形态默认档）。 */
class DeriveWordBandEstimateTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode band(String form, int min, int max) {
        ObjectNode n = M.createObjectNode();
        n.put("form", form);
        n.put("wordsMin", min);
        n.put("wordsMax", max);
        n.put("reason", "题材体量中等");
        return n;
    }

    @Test
    void parse_valid_freeForm() {
        DeriveSampleService.parseWordBand(band("LIGHT_NOVEL", 80000, 150000), null);
    }

    @Test
    void parse_fixedForm_mustEcho() {
        DeriveSampleService.parseWordBand(band("SHORT_STANDALONE", 12000, 25000), "SHORT_STANDALONE");
        assertThrows(LlmJson.Bad.class,
                () -> DeriveSampleService.parseWordBand(band("SHORT_STANDALONE", 12000, 25000), "LONG"));
    }

    @Test
    void parse_unknownForm_rejected() {
        assertThrows(LlmJson.Bad.class,
                () -> DeriveSampleService.parseWordBand(band("EPIC", 100000, 200000), null));
    }

    @Test
    void parse_badBand_rejected() {
        assertThrows(LlmJson.Bad.class,
                () -> DeriveSampleService.parseWordBand(band("LONG", 0, 200000), null));
        assertThrows(LlmJson.Bad.class,
                () -> DeriveSampleService.parseWordBand(band("LONG", 200000, 100000), null));
    }

    @Test
    void parse_missingReason_rejected() {
        ObjectNode n = band("LONG", 100000, 200000);
        n.remove("reason");
        assertThrows(LlmJson.Bad.class, () -> DeriveSampleService.parseWordBand(n, null));
    }

    @Test
    void clamp_insideBand_kept() {
        assertArrayEquals(new int[]{12000, 25000},
                DeriveSampleService.clampBand(12000, 25000, "SHORT_STANDALONE"));
    }

    @Test
    void clamp_greedyAi_pulledIntoFormBand() {
        // AI 给短篇开 50 万 → 整体夹到档上界 3 万（退化为定值档）
        assertArrayEquals(new int[]{30000, 30000},
                DeriveSampleService.clampBand(500000, 800000, "SHORT_STANDALONE"));
        // 长篇估 30~50 万 → 夹到 20 万上界
        assertArrayEquals(new int[]{200000, 200000},
                DeriveSampleService.clampBand(300000, 500000, "LONG"));
        // 半超：15~25 万判 LONG → 保持原值（在 10~20 万档内）
        assertArrayEquals(new int[]{150000, 200000},
                DeriveSampleService.clampBand(150000, 250000, "LONG"));
    }

    @Test
    void clamp_maxBelowFloor_liftedToFloor() {
        // AI 给轻小说估 5千~9千 → 双边抬到档底 8 万（min=max=档底，退化为定值档）
        assertArrayEquals(new int[]{80000, 80000},
                DeriveSampleService.clampBand(5000, 9000, "LIGHT_NOVEL"));
    }

    @Test
    void clamp_unknownForm_throws() {
        assertThrows(BizException.class, () -> DeriveSampleService.clampBand(1, 2, "EPIC"));
    }

    @Test
    void clamp_reversesMinMax() {
        // min>max 的病态输出：先各自钳、再保证 max≥min
        int[] r = DeriveSampleService.clampBand(25000, 15000, "SHORT_STANDALONE");
        assertEquals(25000, r[0]);
        assertEquals(25000, r[1]);
    }
}
