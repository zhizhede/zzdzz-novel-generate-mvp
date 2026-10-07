package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容安全红线锁（2026-10-07 实弹）：供应商内容审核会整段拒答输入里的露骨/高危词
 *（「失身/缠绵/床单殷红」曾让 MiniMax 连续拒绝 ch2 场景请求 → 管线空转）。
 * 全链路生成与修订模板必须带「内容安全红线」——敏情节用中性概述干句、禁用高危词，
 * 一处漏写就可能让该环节的产出重新污染注入链。本测试锁关键模板不被误删。
 */
class ContentSafetyRedlineTest {

    private static final List<String> MUST_CONTAIN = List.of(
            // 生成侧
            LlmNode.SCENE_DRAFT + "|user",
            LlmNode.OUTLINE + "|user",
            LlmNode.DERIVE_RESKIN + "|skin",
            LlmNode.DERIVE_RESKIN + "|chapter",
            LlmNode.DERIVE_OUTLINE + "|user",
            LlmNode.VOLUME_PLAN + "|user",
            LlmNode.DIGEST + "|system",
            LlmNode.SAMPLE_CHAPTER + "|user",
            LlmNode.SAMPLE_VOLUME + "|user",
            LlmNode.SAMPLE_OUTLINE + "|user",
            LlmNode.BOOK_CARDS + "|user",
            LlmNode.CHAPTER_REPLAN + "|user",
            // 修订侧（防止修订轮把风险词写回去）
            LlmNode.CHAPTER_REVISE + "|user",
            LlmNode.READER_FIX + "|user",
            LlmNode.AI_REVIEW_REVISE + "|user"
    );

    @Test
    void allGenerationAndReviseTemplatesCarrySafetyRedline() {
        for (String entry : MUST_CONTAIN) {
            String[] parts = entry.split("\\|");
            String content = PromptCatalog.contentOf(parts[0], parts[1]);
            assertThat(content)
                    .as("模板 %s/%s 必须含内容安全红线", parts[0], parts[1])
                    .contains("内容安全红线");
        }
    }

    @Test
    void redlineBansExplicitVocabularyAndRequiresDrySummary() {
        // 口径锁：禁用词清单 + 中性概述干句的写法要求（措辞变了但语义丢了也要报）
        String scene = PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "user");
        assertThat(scene).contains("失身");       // 禁用词清单里必须列出来
        assertThat(scene).contains("报告式干句");
        assertThat(scene).contains("禁用露骨词");
        // 词表统一：所有模板用同一组高危词示例，防止各处漂移
        for (String entry : MUST_CONTAIN) {
            String[] parts = entry.split("\\|");
            assertThat(PromptCatalog.contentOf(parts[0], parts[1]))
                    .as("模板 %s/%s 高危词表应统一", parts[0], parts[1])
                    .contains("床戏");
        }
    }

    @Test
    void safetyRedlineDoesNotBreakFormatArity() {
        // 红线是纯文本行，不得引入新的 %s/%d——位置参数模板 arity 变了会静默回退（AGENTS 坑 14）
        // SCENE_DRAFT user：20 实参（SceneTimeAnchorTest 已锁）；关键位置模板的 specs 数与已知一致
        int sceneSpecs = PromptTemplateService.specs(
                PromptCatalog.contentOf(LlmNode.SCENE_DRAFT, "user")).stream()
                .filter(s -> !s.equals("%%")).toList().size();
        assertThat(sceneSpecs).isEqualTo(20);
        // OUTLINE user：15 实参（OutlineChapterPovTest 已锁）
        assertThat(PromptTemplateService.specs(
                PromptCatalog.contentOf(LlmNode.OUTLINE, "user"))).hasSize(15);
        // 换皮 chapter：5 实参（ReskinPromptArityTest 已锁）
        assertThat(PromptTemplateService.specs(
                PromptCatalog.contentOf(LlmNode.DERIVE_RESKIN, "chapter"))).hasSize(5);
        // DIGEST system：1 实参（state_spec %s）
        assertThat(PromptTemplateService.specs(
                PromptCatalog.contentOf(LlmNode.DIGEST, "system"))).hasSize(1);
    }
}
