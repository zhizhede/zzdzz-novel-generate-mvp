package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6/QC 的故事规划问题与修复目标。 */
class DeriveGateQcStoryPlanTest {

    @Test
    void planIssueSelectsVolumeRepairTarget() {
        PlanValidationVO validation = new PlanValidationVO(9L, 2, false,
                List.of(new PlanValidationVO.Issue(
                        "ERROR", "REQUIRED_THREAD_OPEN", "PLAN_VOLUME:2",
                        2, 18, List.of(31), "线程未闭合")),
                List.of());

        assertEquals("PLAN_VOLUME:2", DeriveGateService.repairTarget(validation));
        assertTrue(DeriveQcService.planIssueMessages(validation).stream()
                .anyMatch(s -> s.contains("REQUIRED_THREAD_OPEN")));
    }

    @Test
    void gateStatePersistsRepairTarget() {
        String status = DeriveGateService.withGate(
                "{}", "REWORK", 1, List.of("结构问题"), "重排第二卷", "PLAN_VOLUME:2");

        assertEquals("PLAN_VOLUME:2", DeriveGateService.repairTarget(status));
    }
}
