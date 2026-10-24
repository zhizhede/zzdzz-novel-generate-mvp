package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真相卡 solution 键名漂移归一化（run 29 实弹：模型返回 culprit/mote/method，
 * 被三要素校验判成「缺 motive」连废 3 轮，整条全流程中断停摆 9 小时）。
 */
class DeriveTruthNormalizeTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<Integer> CHS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

    private static JsonNode root(String json) throws Exception {
        return M.readTree(json);
    }

    /** 组装带三条合法 clues 的 truth——三要素之后的 clues 校验也要能过，才验得到「归一化生效」。 */
    private static String truthWithClues(String solutionJson) {
        return "{\"truth\":{\"reveal_chapter\":5,"
                + "\"clues\":[{\"clue\":\"栅栏内侧纤维不同\",\"planted_chapter\":2},"
                + "{\"clue\":\"镜面残留指印\",\"planted_chapter\":3},"
                + "{\"clue\":\"符号与私人缩写一致\",\"planted_chapter\":4}],"
                + "\"solution\":" + solutionJson + "}}";
    }

    /** 实弹复现：mote 拼错 → 归一化成 motive → 三要素校验放行。 */
    @Test
    void moteIsNormalizedToMotive() throws Exception {
        JsonNode root = root(truthWithClues(
                "{\"culprit\":\"塞德\",\"mote\":\"为组织攻岛铺路\",\"method\":\"变形魔法\"}"));
        DeriveDraftService.normalizeSolution(root);
        assertEquals("为组织攻岛铺路", root.path("truth").path("solution").path("motive").asText());
        assertDoesNotThrow(() -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
    }

    /** 已有标准键时不覆盖：别名的值不得盖掉正文给的 motive。 */
    @Test
    void existingKeyIsNotOverwritten() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":5,\"solution\":"
                + "{\"culprit\":\"塞德\",\"motive\":\"正解\",\"reason\":\"别名\"}}}");
        DeriveDraftService.normalizeSolution(root);
        assertEquals("正解", root.path("truth").path("solution").path("motive").asText());
    }

    /** method 的别名（means/how/way）同样归一化。 */
    @Test
    void methodAliasesAreNormalized() throws Exception {
        JsonNode root = root(truthWithClues(
                "{\"culprit\":\"塞德\",\"motive\":\"动机\",\"means\":\"缩骨穿栅栏\"}"));
        DeriveDraftService.normalizeSolution(root);
        assertEquals("缩骨穿栅栏", root.path("truth").path("solution").path("method").asText());
        assertDoesNotThrow(() -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
    }

    /** 真没写动机时仍必须拦，且错误信息带模型实际键名（下次一眼定位，无需再翻库）。 */
    @Test
    void missingMotiveStillFailsWithActualKeys() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":5,\"solution\":"
                + "{\"culprit\":\"塞德\",\"method\":\"变形魔法\"}}}");
        DeriveDraftService.normalizeSolution(root);
        LlmJson.Bad e = assertThrows(LlmJson.Bad.class,
                () -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
        assertTrue(e.getMessage().contains("culprit/motive/method"), e.getMessage());
        assertTrue(e.getMessage().contains("实际键：culprit/method"), e.getMessage());
    }

    /** solution 不是对象（模型给成字符串）时归一化不炸，交给校验报错。 */
    @Test
    void nonObjectSolutionIsLeftToValidator() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":5,\"solution\":\"塞德\"}}");
        assertDoesNotThrow(() -> DeriveDraftService.normalizeSolution(root));
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
    }

    /** 无 truth 键（非推理体裁）时归一化是空操作。 */
    @Test
    void missingTruthIsNoop() throws Exception {
        JsonNode root = root("{\"brief\":{\"premise\":\"x\"}}");
        assertDoesNotThrow(() -> DeriveDraftService.normalizeSolution(root));
    }

    /**
     * 判据精确化：clue 里点名凶手不再算泄底。
     * 旧判据「clue 含 culprit 名即拦」把「失踪罪人塞德入狱前曾以吟游诗人身份登岛」这种
     * 可观测事实判成违规，模型被迫改写又撞 reveal 约束（run 29 的 1134→1135 连锁）。
     */
    @Test
    void clueNamingCulpritIsNotLeak() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":5,"
                + "\"clues\":[{\"clue\":\"失踪罪人塞德入狱前曾以吟游诗人身份多次登岛\",\"planted_chapter\":2},"
                + "{\"clue\":\"栅栏内侧纤维与罪人衣物不同\",\"planted_chapter\":3},"
                + "{\"clue\":\"镜面残留指印位置与惯用姿势吻合\",\"planted_chapter\":4}],"
                + "\"solution\":{\"culprit\":\"塞德\",\"motive\":\"为组织攻岛铺路\","
                + "\"method\":\"缩骨变形穿过栅栏\"}}}");
        assertDoesNotThrow(() -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
    }

    /** 但 clue 原样复述 method 的原文片段仍算泄底（答案直接写在线索里）。 */
    @Test
    void clueRepeatingMethodFragmentIsStillLeak() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":5,"
                + "\"clues\":[{\"clue\":\"栅栏上有用可塑形变形胶挤出的缝\",\"planted_chapter\":2},"
                + "{\"clue\":\"栅栏内侧纤维与罪人衣物不同\",\"planted_chapter\":3},"
                + "{\"clue\":\"镜面残留指印位置与惯用姿势吻合\",\"planted_chapter\":4}],"
                + "\"solution\":{\"culprit\":\"塞德\",\"motive\":\"为组织攻岛铺路\","
                + "\"method\":\"用可塑形变形胶挤过栅栏缝隙\"}}}");
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.validateTruth(root.path("truth"), CHS));
    }

    /** 问题清单收集而非早停：一次把毛病记全，避免模型修一个冒一个白烧轮次。 */
    @Test
    void issuesAreCollectedNotShortCircuited() throws Exception {
        JsonNode root = root("{\"truth\":{\"reveal_chapter\":99,\"clues\":[],\"solution\":{}}}");
        List<String> issues = DeriveDraftService.truthIssues(root.path("truth"), CHS);
        assertTrue(issues.size() >= 3, issues.toString());
    }

    /** 降级放行：truth 不合规只留痕不抛；brief.premise 仍是硬要求。 */
    @Test
    void briefTruthDegradesInsteadOfFailing() throws Exception {
        DeriveDraftService.HeadContext ctx = new DeriveDraftService.HeadContext(CHS, List.of(), true);
        JsonNode badTruth = root("{\"brief\":{\"premise\":\"西方奇幻岛屿\"},"
                + "\"truth\":{\"reveal_chapter\":99,\"clues\":[],\"solution\":{}}}");
        assertDoesNotThrow(() -> DeriveDraftService.parseBriefTruth(badTruth, ctx));
        JsonNode noBrief = root("{\"truth\":{\"reveal_chapter\":5}}");
        assertThrows(LlmJson.Bad.class, () -> DeriveDraftService.parseBriefTruth(noBrief, ctx));
    }
}
