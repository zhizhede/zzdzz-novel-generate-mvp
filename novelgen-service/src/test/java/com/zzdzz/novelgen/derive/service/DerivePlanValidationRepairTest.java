package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规划硬校验失败后自动定位卷级返工。 */
class DerivePlanValidationRepairTest {

    @Test
    void collectsDistinctVolumeTargetsAndCarriesConcreteFeedback() {
        PlanValidationVO validation = new PlanValidationVO(38L, null, false,
                List.of(
                        new PlanValidationVO.Issue(
                                "ERROR", "KNOWLEDGE_NOT_ACQUIRED", "PLAN_VOLUME:2",
                                2, 18, List.of(32), "C3 尚未获得擦痕方向"),
                        new PlanValidationVO.Issue(
                                "ERROR", "HIGHLIGHT_CHAIN_MISSING", "PLAN_VOLUME:1",
                                1, 8, List.of(), "第1卷缺角色高光"),
                        new PlanValidationVO.Issue(
                                "ERROR", "LOCAL_CLOSURE_MISSING", "PLAN_VOLUME:2",
                                2, null, List.of(), "第2卷缺闭合")),
                List.of());

        assertEquals(List.of(2, 1), DerivePlanService.repairVolumes(validation));
        String feedback = DerivePlanService.validationFeedback(validation);
        assertTrue(feedback.contains("KNOWLEDGE_NOT_ACQUIRED"));
        assertTrue(feedback.contains("C3 尚未获得擦痕方向"));
    }

    @Test
    void nonVolumeProblemsStayForExplicitUpstreamRepair() {
        PlanValidationVO validation = new PlanValidationVO(38L, null, false,
                List.of(new PlanValidationVO.Issue(
                        "ERROR", "FINAL_ROLE_MISSING", "SPINE",
                        null, null, List.of(), "最终卷角色错误")),
                List.of());

        assertTrue(DerivePlanService.repairVolumes(validation).isEmpty());
    }
}
