package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.llm.contract.ContractViolation;
import com.zzdzz.novelgen.llm.contract.NodeOutputContract;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import com.zzdzz.novelgen.service.PromptTemplateService;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmJsonStructuredContractTest {

    @Test
    void parserViolationIsFedBackWithPath() {
        LlmPort llm = mock(LlmPort.class);
        PromptTemplateService promptTemplates = mock(PromptTemplateService.class);
        LlmCallLogDataService callLogDAO = mock(LlmCallLogDataService.class);
        when(promptTemplates.getSection(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, String> params = invocation.getArgument(2);
                    return params.get("reason");
                });
        when(llm.chat(any())).thenReturn(
                new LlmPort.ChatResult(11L, "{\"value\":\"bad\"}", null, null),
                new LlmPort.ChatResult(12L, "{\"value\":42}", null, null));

        NodeOutputContract<String> contract = new NodeOutputContract<>(
                "derive.test", "derive_plan", 1, null, "schema fragment",
                parserViolatesAtValue(),
                value -> List.of());
        LlmJson llmJson = new LlmJson(llm, promptTemplates, callLogDAO);
        LlmPort.ChatRequest request = request();

        assertThat(llmJson.askStructured(contract, request, 2)).isEqualTo("42");

        ArgumentCaptor<LlmPort.ChatRequest> captor = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(2)).chat(captor.capture());
        String firstMessage = captor.getAllValues().get(0).messages().get(1).content();
        assertThat(firstMessage).contains("schema fragment");
        String secondMessage = captor.getAllValues().get(1).messages().get(1).content();
        assertThat(secondMessage)
                .contains("$.value")
                .contains("integer")
                .contains("bad");
    }

    @Test
    void duplicateStateKeyReportsDuplicateKeyNotSeqFilled() {
        LlmPort llm = mock(LlmPort.class);
        LlmCallLogDataService callLogDAO = mock(LlmCallLogDataService.class);
        when(llm.chat(any())).thenReturn(
                new LlmPort.ChatResult(21L, "{\"state\":{\"C1\":\"a\",\"C1\":\"b\"}}", null, null));
        NodeOutputContract<String> contract = new NodeOutputContract<>(
                "derive.test", "derive_plan", 1, null, "",
                node -> node.toString(),
                value -> List.of());
        LlmJson llmJson = new LlmJson(llm, mock(PromptTemplateService.class), callLogDAO);

        assertThatThrownBy(() -> llmJson.askStructured(contract, request(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[SCHEMA_DUPLICATE_KEY]")
                .hasMessageNotContaining("seq")
                .hasMessageNotContaining("filled");

        ArgumentCaptor<LlmCallLogDO> logCaptor = ArgumentCaptor.forClass(LlmCallLogDO.class);
        verify(callLogDAO).updateById(logCaptor.capture());
        assertThat(logCaptor.getValue().getErrorMsg())
                .startsWith("CONTRACT_REJECTED|")
                .contains("[SCHEMA_DUPLICATE_KEY]");
    }

    @Test
    void allAttemptsFailReturnsLastViolations() {
        LlmPort llm = mock(LlmPort.class);
        PromptTemplateService promptTemplates = mock(PromptTemplateService.class);
        LlmCallLogDataService callLogDAO = mock(LlmCallLogDataService.class);
        when(promptTemplates.getSection(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, String> params = invocation.getArgument(2);
                    return params.get("reason");
                });
        when(llm.chat(any())).thenReturn(
                new LlmPort.ChatResult(31L, "{\"value\":1}", null, null),
                new LlmPort.ChatResult(32L, "{\"value\":1}", null, null));
        NodeOutputContract<String> contract = new NodeOutputContract<>(
                "derive.test", "derive_plan", 1, null, "",
                parserAlwaysViolates(),
                value -> List.of());
        LlmJson llmJson = new LlmJson(llm, promptTemplates, callLogDAO);

        assertThatThrownBy(() -> llmJson.askStructured(contract, request(), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[SCHEMA_FIELD_INVALID]")
                .hasMessageContaining("$.value")
                .hasMessageContaining("integer")
                .hasMessageContaining("bad");
    }

    private static Function<JsonNode, String> parserAlwaysViolates() {
        return node -> { throw new LlmJson.Bad(new ContractViolation("SCHEMA_FIELD_INVALID", "$.value", "值必须是整数", "integer", "bad").feedbackLine()); };
    }

    private static Function<JsonNode, String> parserViolatesAtValue() {
        return node -> {
            if (!node.path("value").isInt()) {
                throw new LlmJson.Bad(new ContractViolation(
                        "SCHEMA_FIELD_INVALID",
                        "$.value",
                        "值必须是整数",
                        "integer",
                        node.path("value").asText()).feedbackLine());
            }
            return node.get("value").asText();
        };
    }

    private static LlmPort.ChatRequest request() {
        return new LlmPort.ChatRequest(
                "derive_plan", 1L, 2L, 3L,
                List.of(
                        LlmPort.Message.system("system"),
                        LlmPort.Message.user("user")),
                0.2);
    }
}