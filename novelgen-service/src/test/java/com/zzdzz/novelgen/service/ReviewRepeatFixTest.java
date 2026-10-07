package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 去复沓修订「取优不取新」（2026-10-05）：读者评审通过但复沓超阈值时治一轮，
 * 修订稿**只有复沓清单确实更短、且复审没判 BLOCKER**才被采纳，否则保留原稿。
 * 原先是无条件采纳——把「治复沓反而治坏」的稿子直接发货。
 */
class ReviewRepeatFixTest {

    private static final long NOVEL_ID = 56L;
    private static final long CHAPTER_ID = 441L;
    private static final String ORIGINAL = "原稿正文。王五推门进来，冷光照在他脸上。";
    private static final String POLISHED = "修订后的正文。王五推门进来。";

    private static final ObjectMapper M = new ObjectMapper();

    private LlmPort llmPort;
    private LlmJson llmJson;
    private GateService gateService;
    private ReviewService service;
    private ChapterDO chapter;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        llmJson = mock(LlmJson.class);
        gateService = mock(GateService.class);
        service = new ReviewService(llmPort, llmJson, mock(ContextPackerService.class),
                mock(GateReportDataService.class), mock(ChapterDataService.class),
                mock(com.zzdzz.novelgen.service.data.SceneDataService.class), M,
                mock(TuningService.class), mock(PromptTemplateService.class), gateService, mock(StageLog.class));
        chapter = new ChapterDO();
        chapter.setId(CHAPTER_ID);
        chapter.setNovelId(NOVEL_ID);
        chapter.setChapterNo(9);
        chapter.setBudgetMin(2000);
        chapter.setBudgetMax(2500);
        // perNovel 走 gateService.configValue（书级优先）：mock 的 double 默认是 0.0，
        // 会让复沓阈值与长度护栏全失真（长度上限 0 会把任何修订稿判成「超长弃用」）
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_repeat_fix_min"), anyDouble())).thenReturn(3.0);
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_fix_len_max"), anyDouble())).thenReturn(1.15);
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_fix_len_min"), anyDouble())).thenReturn(0.75);
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_fat_ratio_block"), anyDouble())).thenReturn(0.33);
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_fat_ratio_hard"), anyDouble())).thenReturn(0.5);
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_structural_block"), anyDouble())).thenReturn(1.0);
    }

    /** 评审 JSON：verdict + 复沓清单条数（ObjectNode，便于在用例里补字段）。 */
    private static com.fasterxml.jackson.databind.node.ObjectNode reviewNode(String verdict, int repeats) {
        return (com.fasterxml.jackson.databind.node.ObjectNode) review(verdict, repeats);
    }

    /** 评审 JSON：verdict + 复沓清单条数。 */
    private static JsonNode review(String verdict, int repeats) {
        var obj = M.createObjectNode();
        obj.put("verdict", verdict);
        var arr = obj.putArray("repeat");
        for (int i = 0; i < repeats; i++) {
            arr.add("复沓句 " + i);
        }
        return obj;
    }

    /** 两轮评审的返回值（首轮 r1、复审 r2）。 */
    private void reviewsReturn(JsonNode r1, JsonNode r2) {
        when(llmJson.ask(any(), any(), anyInt(), any())).thenReturn(r1, r2);
    }

    private void polishReturns(String text) {
        when(llmPort.chat(any())).thenReturn(new LlmPort.ChatResult(1L, text, null, null));
    }

    /** 复沓变少且复审仍通过 → 采纳修订稿。 */
    @Test
    void adoptsPolishedDraftWhenRepeatsDrop() {
        reviewsReturn(review("pass", 4), review("pass", 2));
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isEqualTo(POLISHED);
        assertThat(outcome.blocked()).isFalse();
    }

    /** 复沓没变少 → 保留原稿（不把没改善的重写塞给管线）。 */
    @Test
    void keepsOriginalWhenRepeatCountIsUnchanged() {
        reviewsReturn(review("pass", 4), review("pass", 4));
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
        assertThat(outcome.verdict()).isEqualTo("pass");
        assertThat(outcome.blocked()).isFalse();
    }

    /** 复沓反而变多 → 保留原稿。 */
    @Test
    void keepsOriginalWhenRepeatCountGrows() {
        reviewsReturn(review("pass", 3), review("pass", 5));
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
    }

    /** 复沓虽变少但复审结构性四问未过（真 BLOCKER）→ 不换（换过去等于把章直接拖进处置流程）。 */
    @Test
    void keepsOriginalWhenRecheckVerdictIsBlocker() {
        var r2 = reviewNode("blocker", 1);
        r2.put("continuity", "fail"); // 结构性硬伤才算真 BLOCKER：只有 fat 超标的 blocker 会被降级成 pass
        reviewsReturn(review("pass", 4), r2);
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
        assertThat(outcome.verdict()).isEqualTo("pass");
        assertThat(outcome.blocked()).isFalse();
    }

    /** 复审解析失败（fail-open 空节点，没有 repeat 数组）→ 不换：解析失败不等于零复沓。 */
    @Test
    void keepsOriginalWhenRecheckCouldNotBeParsed() {
        var failedOpen = M.createObjectNode();
        failedOpen.put("verdict", "pass").put("summary", "解析失败跳过");
        reviewsReturn(review("pass", 4), failedOpen);
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
    }

    /** 复沓没到阈值（评审通过且清单短）→ 不治：一次 LLM 都不花。 */
    @Test
    void doesNotPolishBelowThreshold() {
        reviewsReturn(review("pass", 2), review("pass", 0));

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
        assertThat(outcome.verdict()).isEqualTo("pass");
    }

    /** 复沓阈值写 0 ＝关闭该修订（原先 0 反向生效：size()>=0 恒真 ⇒ 每章都治）。 */
    @Test
    void repeatFixMinZeroDisablesTheRepair() {
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_repeat_fix_min"), anyDouble())).thenReturn(0.0);
        reviewsReturn(review("pass", 9), review("pass", 9));

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.revised()).isNull();
        assertThat(outcome.verdict()).isEqualTo("pass");
    }

    /** reader_structural_block=0：结构性四问未过也只报不拦（报报告，不阻塞过稿）。 */
    @Test
    void structuralFailIsReportedNotBlockedWhenSwitchOff() {
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_structural_block"), anyDouble())).thenReturn(0.0);
        var r1 = reviewNode("blocker", 0);
        r1.put("hook", "fail");
        reviewsReturn(r1, r1);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.verdict()).isEqualTo("pass");
        assertThat(outcome.blocked()).isFalse();
    }

    /** 默认（开关=1）：结构性四问未过仍拦——回退到老行为不该被这条开关默默改掉。 */
    @Test
    void structuralFailStillBlocksByDefault() {
        var r1 = reviewNode("blocker", 0);
        r1.put("hook", "fail");
        reviewsReturn(r1, r1);
        polishReturns(POLISHED); // 走得进 BLOCKER 重写轮

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.blocked()).isTrue();
    }

    /** 注水超硬上限：任何口径下都拦，不受结构性开关影响。 */
    @Test
    void fatHardCapStillBlocksRegardlessOfSwitches() {
        when(gateService.configValue(eq(NOVEL_ID), eq("reader_structural_block"), anyDouble())).thenReturn(0.0);
        var r1 = reviewNode("blocker", 0);
        r1.put("fat_ratio", 0.9);
        reviewsReturn(r1, r1);
        polishReturns(POLISHED);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.blocked()).isTrue();
    }

    /** 仅注水比超软阈值、四问全过 → 真的降级为 pass（原先只写 note 不改 verdict，等于从未生效）。 */
    @Test
    void fatOnlyDowngradeActuallyFlipsVerdict() {
        var r1 = reviewNode("blocker", 0);
        r1.put("fat_ratio", 0.4);
        reviewsReturn(r1, r1);

        var outcome = service.readerReviewAndFix(NOVEL_ID, chapter, ORIGINAL, null);

        assertThat(outcome.verdict()).isEqualTo("pass");
        assertThat(outcome.blocked()).isFalse();
    }

    /** 取优判据本身（纯函数）：缺 repeat 数组、复审判 BLOCKER 等情况一律「不更好」。 */
    @Test
    void betterJudgementIsConservative() {
        assertThat(ReviewService.repeatFixBetter(review("pass", 5), review("pass", 4))).isTrue();
        assertThat(ReviewService.repeatFixBetter(review("pass", 5), review("pass", 5))).isFalse();
        var structuralBlocker = reviewNode("blocker", 1);
        structuralBlocker.put("hook", "fail");
        assertThat(ReviewService.repeatFixBetter(review("pass", 5), structuralBlocker)).isFalse();
        assertThat(ReviewService.repeatFixBetter(review("pass", 5), M.createObjectNode())).isFalse();
        assertThat(ReviewService.repeatFixBetter(M.createObjectNode(), review("pass", 1))).isFalse();
        assertThat(ReviewService.repeatFixBetter(
                review("pass", 5), M.createObjectNode().put("verdict", "pass"))).isFalse();
    }

    /**
     * 真实模型输出回放：fixtures 是 gate_reports.result 的原文（2026-10-04/05 书 56 实跑的两轮读者评审）。
     * 四次去复沓修订里三次没变好——441（3→8）、442（6→6）、456（3→6 且复审判 blocker），
     * 老代码「取新不取优」把它们全都采纳了；新判据只放行真正变短的那次（457：4→3）。
     */
    @Test
    void replayRealReviewOutputsFromBookFiftySix() throws Exception {
        assertThat(better("reader_review_ch441_r1_repeat3.json", "reader_review_ch441_r2_repeat8.json")).isFalse();
        assertThat(better("reader_review_ch442_r1_repeat6.json", "reader_review_ch442_r2_repeat6.json")).isFalse();
        assertThat(better("reader_review_ch456_r1_repeat3.json", "reader_review_ch456_r2_blocker_repeat6.json")).isFalse();
        assertThat(better("reader_review_ch457_r1_repeat4.json", "reader_review_ch457_r2_repeat3.json")).isTrue();
    }

    private static boolean better(String beforeFile, String afterFile) throws Exception {
        return ReviewService.repeatFixBetter(load(beforeFile), load(afterFile));
    }

    private static JsonNode load(String name) throws Exception {
        try (var in = ReviewRepeatFixTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("测试夹具缺失: " + name).isNotNull();
            return M.readTree(in.readAllBytes());
        }
    }
}
