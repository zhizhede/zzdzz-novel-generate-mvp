package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.enums.LlmCallEffectiveStatusEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmCallEffectiveStatusTest {

    @Test
    void statusOkWithErrorIsContentRejected() {
        assertEquals(LlmCallEffectiveStatusEnum.CONTENT_REJECTED,
                LlmCallEffectiveStatusEnum.effectiveStatus("ok", "SCHEMA_FIELD_INVALID path=$"));
    }

    @Test
    void statusOkWithoutErrorIsAccepted() {
        assertEquals(LlmCallEffectiveStatusEnum.ACCEPTED,
                LlmCallEffectiveStatusEnum.effectiveStatus("ok", null));
    }

    @Test
    void errorContainingInterruptedIsInterrupted() {
        assertEquals(LlmCallEffectiveStatusEnum.INTERRUPTED,
                LlmCallEffectiveStatusEnum.effectiveStatus("error", "stream interrupted"));
    }

    @Test
    void statusErrorIsCallFailed() {
        assertEquals(LlmCallEffectiveStatusEnum.CALL_FAILED,
                LlmCallEffectiveStatusEnum.effectiveStatus("error", "timeout"));
    }
}


