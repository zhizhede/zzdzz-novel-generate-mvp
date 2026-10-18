package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P2 裁决：推理/悬疑题材抽中开放一律改判闭合（真相卡闭合义务优先）。 */
class DeriveSampleServiceOutcomeTest {

    @Test
    void mysteryTagOpenCoercedToClosed() {
        assertEquals("闭合", DeriveSampleService.coerceMysteryOutcome("开放", List.of("推理", "奇幻")));
        assertEquals("闭合", DeriveSampleService.coerceMysteryOutcome("开放", List.of("冒险", "悬疑")));
        assertEquals("闭合", DeriveSampleService.coerceMysteryOutcome("循环", List.of("推理", "奇幻")),
                "run18 教训：循环同样允许留悬念，悬疑题材一并改判闭合");
    }

    @Test
    void nonMysteryTagKeepsOpen() {
        assertEquals("开放", DeriveSampleService.coerceMysteryOutcome("开放", List.of("冒险", "奇幻")));
        assertEquals("开放", DeriveSampleService.coerceMysteryOutcome("开放", List.of()));
        assertEquals("循环", DeriveSampleService.coerceMysteryOutcome("循环", List.of("冒险", "奇幻")));
    }

    @Test
    void otherOutcomesUntouched() {
        assertEquals("闭合", DeriveSampleService.coerceMysteryOutcome("闭合", List.of("推理")));
    }

    @Test
    void nullTagsKeepsOpen() {
        assertEquals("开放", DeriveSampleService.coerceMysteryOutcome("开放", null));
    }
}
