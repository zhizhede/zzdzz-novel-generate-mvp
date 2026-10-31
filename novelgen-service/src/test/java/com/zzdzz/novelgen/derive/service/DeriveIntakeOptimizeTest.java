package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1 输入思路 AI 优化：结构校验 / 开关守卫 / LLM 失败 fail-open 用原思路。 */
class DeriveIntakeOptimizeTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final LlmJson llmJson = mock(LlmJson.class);
    private final DeriveSampleService svc =
            new DeriveSampleService(null, null, null, null, null, null, null, null, llmJson, OM);

    private static RunCreateDTO dto(Boolean intakeOptimize, String intent) {
        return new RunCreateDTO(10000, 30000, List.of("推理"), "0.75",
                42L, null, null, false, intent, null, intakeOptimize, null, null);
    }

    @Test
    void parseIntake_acceptsCompleteOutput() throws Exception {
        JsonNode root = OM.readTree("""
                {"score":6.7,"holes":["遛狗必然被目击"],"changes":["改为极少出门"],
                 "optimized_intent":"优化后的思路正文"}
                """);
        assertEquals(root, DeriveSampleService.parseIntake(root));
    }

    @Test
    void parseIntake_rejectsBadShapes() throws Exception {
        JsonNode noIntent = OM.readTree("{\"score\":5,\"holes\":[],\"changes\":[]}");
        assertThrows(LlmJson.Bad.class, () -> DeriveSampleService.parseIntake(noIntent));

        JsonNode badScore = OM.readTree(
                "{\"score\":11,\"holes\":[],\"changes\":[],\"optimized_intent\":\"x\"}");
        assertThrows(LlmJson.Bad.class, () -> DeriveSampleService.parseIntake(badScore));

        JsonNode noArrays = OM.readTree(
                "{\"score\":5,\"optimized_intent\":\"x\"}");
        assertThrows(LlmJson.Bad.class, () -> DeriveSampleService.parseIntake(noArrays));
    }

    @Test
    void validate_requiresIntentWhenToggleOn() {
        assertThrows(BizException.class, () -> svc.validate(dto(true, null)),
                "开关开启且 intent 空 → PARAM_ERROR");
        assertThrows(BizException.class, () -> svc.validate(dto(true, "  ")));
        svc.validate(dto(true, "有思路"));
        svc.validate(dto(false, null)); // 开关关：intent 可空
    }

    @Test
    void optimizeIntake_failOpen_usesOriginalWithErrorResponse() {
        when(llmJson.ask(any(), any(), anyInt())).thenThrow(new RuntimeException("LLM挂了"));
        DeriveSampleService.IntakeResult r = svc.optimizeIntake(dto(true, "原始思路"));
        assertEquals("原始思路", r.intent(), "失败时用原思路（fail-open）");
        assertNotNull(r.review());
        assertEquals("USER", r.review().path("source").asText());
        assertTrue(r.review().path("error").asText().contains("LLM挂了"));
    }
}
