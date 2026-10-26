package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P6 Task4：persona/角色合同对齐校验。 */
class DerivePersonaAlignmentTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void castRoleIsPersistedInCharacterContract() throws Exception {
        JsonNode cast = M.readTree("""
                [{"name":"乔晚","role":"受托追查失踪案的调查者","characterKey":"C1"}]
                """);
        var base = new PlanContracts.CharacterContract(
                "C1", "乔晚", PlanContracts.CharacterFunction.DETECTIVE, "唯一", "查清", "执着",
                List.of(), List.of(), "冲突", "选择", "代价", List.of("REL-C1-C2"),
                "兑现", 1, 1, 1, "开始", "结束", null);

        var enriched = DeriveStoryPlanService.applyCastRoles(cast, List.of(base));

        assertEquals("受托追查失踪案的调查者", enriched.get(0).castRole());
        assertEquals("乔晚", enriched.get(0).displayNameHint());
    }

    @Test
    void personaProtagonistActionConflictIsReported() throws Exception {
        CharacterPlanDO row = character("C1", "乔晚", "DETECTIVE");
        row.setArcBefore("受托到场的遗属，被动复盘");
        row.setArcAfter("完成立场转换，私自进入旧楼现场取证");
        row.setContract("{}");
        JsonNode cast = M.readTree("""
                [{"name":"乔晚","characterKey":"C1",
                  "persona":{"conduct":"禁止私自进入旧楼现场"}}]
                """);

        var issues = DerivePlanValidationService.validatePersonaAlignment(List.of(row), cast);

        assertTrue(issues.stream().anyMatch(i -> "PERSONA_ROLE_MISMATCH".equals(i.code())),
                "persona 绝对禁令与角色计划动作冲突必须上报：" + issues);
        assertTrue(issues.stream().allMatch(i -> i.repairScope() == PlanContracts.RepairScope.DRAFT),
                "persona 冲突 repairTarget 必须是 DRAFT");
    }

    @Test
    void detectiveAndOppositionCannotShareName() throws Exception {
        CharacterPlanDO detective = character("C1", "乔晚", "DETECTIVE");
        detective.setContract("{}");
        CharacterPlanDO opposition = character("C2", "乔晚", "OPPOSITION");
        opposition.setContract("{}");

        var issues = DerivePlanValidationService.validatePersonaAlignment(
                List.of(detective, opposition), null);

        assertTrue(issues.stream().anyMatch(
                        i -> "CHARACTER_FUNCTION_NAME_COLLISION".equals(i.code())),
                "DETECTIVE 与 OPPOSITION 共用姓名必须上报：" + issues);
    }

    @Test
    void c1MustBeDetectiveMainView() throws Exception {
        CharacterPlanDO row = character("C1", "乔晚", "SUPPORT");
        row.setContract("{}");

        var issues = DerivePlanValidationService.validatePersonaAlignment(List.of(row), null);

        assertTrue(issues.stream().anyMatch(i ->
                        "PERSONA_ROLE_MISMATCH".equals(i.code())
                                && i.message().contains("C1")),
                "C1 非 DETECTIVE 必须上报：" + issues);
    }

    @Test
    void alignedCastProducesNoIssue() throws Exception {
        CharacterPlanDO row = character("C1", "乔晚", "DETECTIVE");
        row.setArcBefore("受托到场");
        row.setArcAfter("独立调查者完成取证");
        row.setContract("{}");
        JsonNode cast = M.readTree("""
                [{"name":"乔晚","characterKey":"C1",
                  "role":"受托追查失踪案的调查者",
                  "persona":{"conduct":"每条线索必留书面记录"}}]
                """);

        var issues = DerivePlanValidationService.validatePersonaAlignment(List.of(row), cast);

        assertTrue(issues.isEmpty(), "对齐的 persona 不应报错：" + issues);
    }

    @Test
    void restatedProhibitionAndNegatedUsageAreNotConflicts() {
        // run45 实弹第3轮熔断：arc_before 复述禁令本身、FC 用否定式引用禁令，
        // 均为合规表述，纯子串包含会误判 → 连续 4 轮 PERSONA_ROLE_MISMATCH 回炉不收敛。
        String conduct = "绝不提前下结论，必逐条核对时间线与物证";
        assertNull(DerivePlanValidationService.prohibitedActionConflict(
                conduct, "冷静克制、绝不提前下结论，只信物证与时间线"));
        assertNull(DerivePlanValidationService.prohibitedActionConflict(
                conduct, "程砚必须在不提前下结论的前提下反复追问以剥离噪音"));
        assertNull(DerivePlanValidationService.prohibitedActionConflict(
                conduct, "陆则焦虑时会避免提前下结论并等待物证"));
    }

    @Test
    void bareProhibitedActionStillConflicts() {
        String conduct = "绝不提前下结论，必逐条核对时间线与物证";
        assertEquals("提前下结论", DerivePlanValidationService.prohibitedActionConflict(
                conduct, "遇事提前下结论，武断收场，读者一眼看穿"));
        assertEquals("提前下结论", DerivePlanValidationService.prohibitedActionConflict(
                conduct, "他习惯于提前下结论"));
    }

    private static CharacterPlanDO character(String key, String name, String function) {
        CharacterPlanDO row = new CharacterPlanDO();
        row.setCharacterKey(key);
        row.setDisplayNameHint(name);
        row.setFunctionCode(function);
        return row;
    }
}