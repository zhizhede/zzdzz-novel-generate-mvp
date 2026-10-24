package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** PLAN_V2 与历史 PLAN 的状态机分流。 */
@ExtendWith(MockitoExtension.class)
class DerivePlanOrchestrationV2Test {

    @Mock
    private DeriveStoryPlanService storyPlanService;
    @Mock
    private DeriveBeatRouteService beatRouteService;
    @Mock
    private DerivePlanValidationService validationService;
    @InjectMocks
    private DerivePlanService service;

    @Test
    void newRunWalksScaleSpineVolumesCharactersRoutesThenValidate() {
        when(storyPlanService.advance(9L)).thenReturn(new PlanVO(
                9L, 0, 48, 0, 0, List.of(), "SPINE", 0, 5, 0, 0, false));
        RunDO run = new RunDO();
        run.setId(9L);
        run.setStatus("PLANNING");
        run.setStepStatus("{}");

        PlanVO vo = service.planV2ForTest(run);

        assertEquals("SPINE", vo.unit());
    }

    @Test
    void legacyRunWithStepsPlanDoesNotEnterPlanV2() {
        RunDO run = new RunDO();
        run.setStepStatus("{\"v\":1,\"steps\":{\"PLAN\":{\"chapterCount\":8}}}");

        assertTrue(DerivePlanService.shouldUseLegacy(run));
    }
}
