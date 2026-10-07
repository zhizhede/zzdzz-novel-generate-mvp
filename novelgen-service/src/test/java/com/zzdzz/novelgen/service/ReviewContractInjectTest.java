package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.PromptCatalog;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.SceneDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审校硬约束契约注入（2026-10-07 漏判实证）：同一处称呼硬伤（「太太」应为苏栖棠）
 * 早间判 blocker、晚间同文本首审整条漏掉直接放行——自由找茬式审校是单次采样，把握度边界上必然漏。
 * 根治：写读同表的核销制——审校输入带【本章章纲】【本章场景任务】，系统提示要求
 * 称谓/目标/时序三项核销逐条过，核销违例必须上报（对「宁可漏报」开豁免口）。
 * 双头锁：模板占位存在（catalog）+ 代码供值到位（getSection params），缺一头 getSection 都会静默填空。
 */
class ReviewContractInjectTest {

    private static final long NOVEL_ID = 63L;
    private static final ObjectMapper M = new ObjectMapper();

    private LlmPort llmPort;
    private LlmJson llmJson;
    private ContextPackerService packer;
    private PromptTemplateService promptTemplates;
    private SceneDataService sceneData;
    private GateService gateService;
    private ReviewService service;
    private ChapterDO chapter;

    @BeforeEach
    void setUp() {
        llmPort = mock(LlmPort.class);
        llmJson = mock(LlmJson.class);
        packer = mock(ContextPackerService.class);
        promptTemplates = mock(PromptTemplateService.class);
        sceneData = mock(SceneDataService.class);
        gateService = mock(GateService.class);
        service = new ReviewService(llmPort, llmJson, packer,
                mock(GateReportDataService.class), mock(ChapterDataService.class), sceneData,
                M, mock(TuningService.class), promptTemplates, gateService, mock(StageLog.class));
        chapter = new ChapterDO();
        chapter.setId(510L);
        chapter.setNovelId(NOVEL_ID);
        chapter.setChapterNo(1);
        chapter.setGoal("确认岑宛之已无生机、庄票结清尾款");
        chapter.setHook("尾款用庄票结，今夜就转");
        chapter.setFullText("正文");
        when(promptTemplates.getSection(eq(LlmNode.AI_REVIEW), eq("user"), any()))
                .thenReturn("rendered-user");
        when(promptTemplates.get(LlmNode.AI_REVIEW, "system")).thenReturn("rendered-system");
        when(gateService.configValue(eq(NOVEL_ID), eq("ai_review_rounds"), anyDouble())).thenReturn(3.0);
        // 首轮即过：本测试只锁输入契约，不测轮次（轮次由 ReviewMultiRoundTest 锁）
        when(llmJson.ask(any(), any(), eq(2), any())).thenReturn(firstRoundPass());
    }

    private static JsonNode firstRoundPass() {
        var obj = M.createObjectNode();
        obj.put("verdict", "pass");
        obj.put("summary", "无硬伤");
        obj.putArray("issues");
        return obj;
    }

    /** 模板侧：system 带三项核销与豁免口，user 带章纲/场景任务占位。 */
    @Test
    void catalogTemplatesCarryContractChecklist() {
        String system = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "system");
        assertThat(system).contains("称谓核销");
        assertThat(system).contains("目标核销");
        assertThat(system).contains("时序核销");
        // 「宁可漏报」必须对核销违例开豁免口，否则核销制被原规则一句话压回漏判
        assertThat(system).contains("不适用「没有把握不报」");
        String user = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "user");
        assertThat(user).contains("{chapter_goal}");
        assertThat(user).contains("{scene_goals}");
    }

    /** 代码侧：userPrompt 把章纲目标/钩子与场景任务（含禁现红线）真实供进 getSection。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void userPromptSuppliesContractValues() {
        SceneDO s1 = new SceneDO();
        s1.setSceneNo(1);
        s1.setGoal("码头封门，贵客现身");
        s1.setMustReveal("护卫八人一排入场");
        s1.setMustNot("不得点破贵客身份");
        s1.setTimeAnchor("当夜三更");
        when(sceneData.findByChapter(510L)).thenReturn(List.of(s1));

        service.reviewAndFix(NOVEL_ID, chapter, "正文", null);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass((Class) Map.class);
        org.mockito.Mockito.verify(promptTemplates)
                .getSection(eq(LlmNode.AI_REVIEW), eq("user"), captor.capture());
        Map<String, String> params = captor.getValue();

        assertThat(params.get("chapter_goal"))
                .contains("确认岑宛之已无生机、庄票结清尾款")
                .contains("尾款用庄票结，今夜就转");
        assertThat(params.get("scene_goals"))
                .contains("场景1")
                .contains("码头封门，贵客现身")
                .contains("必现：护卫八人一排入场")
                .contains("禁现：不得点破贵客身份");

        // 模板里每个 {key} 占位都必须有供值——缺一头 getSection 静默填空，功能死了都没人知道
        String template = PromptCatalog.contentOf(LlmNode.AI_REVIEW, "user");
        var matcher = Pattern.compile("\\{(\\w+)}").matcher(template);
        while (matcher.find()) {
            assertThat(params)
                    .as("模板占位 {%s} 必须有供值", matcher.group(1))
                    .containsKey(matcher.group(1));
        }
    }
}
