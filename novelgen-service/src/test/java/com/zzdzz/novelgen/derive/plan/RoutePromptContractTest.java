package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.llm.PromptCatalog;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROUTE v2 提示词契约：模板 token、完整枚举与渲染后 schema 片段。 */
class RoutePromptContractTest {

    private static final Pattern UNRESOLVED_TOKEN = Pattern.compile("\\{\\{[A-Z_]+\\}\\}");

    @Test
    void routePromptListsEveryBeatRoleName() {
        String rendered = StoryPlanningPrompts.ROUTE;

        for (PlanContracts.BeatRole role : PlanContracts.BeatRole.values()) {
            assertTrue(rendered.contains(role.name()),
                    "渲染后的 ROUTE 提示词缺少 beatRole: " + role.name());
        }
    }

    @Test
    void routePromptForbidsBeatRoleInModelOutput() {
        String rendered = StoryPlanningPrompts.ROUTE;

        assertTrue(rendered.contains("beatRole"), "提示词应说明 beatRole 的禁用口径");
        assertTrue(rendered.contains("禁止"), "提示词必须明确禁止模型输出结构字段");
        assertFalse(rendered.contains("\"beatRole\":"), "渲染后的提示词不得包含 legacy beatRole 输出示例");
    }

    @Test
    void routePromptDefinesStateValueAsArray() {
        String rendered = StoryPlanningPrompts.ROUTE;

        assertTrue(rendered.contains("stateEffects"), "提示词缺少 stateEffects 字段说明");
        assertTrue(rendered.contains("数组"), "stateEffects 必须明确为数组");
        assertTrue(rendered.contains("kind"), "stateEffects 必须说明 kind 字段");
    }

    @Test
    void renderedRoutePromptMatchesCatalogTemplateAfterTokenResolution() {
        String template = PromptCatalog.contentOf("derive_plan", "system");
        String rendered = StoryPlanningPrompts.ROUTE;
        String resolved = resolveTemplate(template);

        assertTrue(rendered.startsWith(resolved),
                "渲染后的 ROUTE 提示词必须由 catalog 模板解析 token 后得到");
        assertTrue(rendered.contains(com.zzdzz.novelgen.derive.plan.PlanContractSchemas
                        .routeNarrative().promptFragment()),
                "渲染后的 ROUTE 提示词必须包含 ROUTE v2 schema 片段");
    }

    @Test
    void routePromptHasNoUnresolvedTemplateToken() {
        assertFalse(UNRESOLVED_TOKEN.matcher(StoryPlanningPrompts.ROUTE).find(),
                "渲染后的 ROUTE 提示词不得残留模板 token");
    }

    private static String resolveTemplate(String template) {
        return template
                .replace("{{BEAT_ROLES}}", bulletList(PlanContracts.BeatRole.values()))
                .replace("{{STORY_LAYERS}}", bulletList(PlanContracts.StoryLayer.values()))
                .replace("{{APPEARANCE_MODES}}", bulletList(PlanContracts.AppearanceMode.values()))
                .replace("{{STATE_CHANGE_KINDS}}", bulletList(PlanContracts.StateChangeKind.values()))
                .replace("{{THREAD_EFFECT_KINDS}}", bulletList(PlanContracts.ThreadEffectKind.values()));
    }

    private static String bulletList(Enum<?>[] values) {
        StringBuilder text = new StringBuilder();
        for (Enum<?> value : values) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("- ").append(value.name());
        }
        return text.toString();
    }
}
