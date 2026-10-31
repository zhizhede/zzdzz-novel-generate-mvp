package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.llm.PromptCatalog;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROUTE 提示词契约：模板 token、完整枚举与旧路由解析器字段。 */
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

        assertTrue(rendered.contains("\"beatRole\""), "旧路由契约必须要求 beatRole 字段");
        assertTrue(rendered.contains("\"chapterNo\""), "旧路由契约必须要求 chapterNo 字段");
        assertFalse(rendered.contains("beatNarratives"),
                "渲染后的 ROUTE 提示词不得混入 ROUTE v2 叙事契约");
    }

    @Test
    void routePromptDefinesStateValueAsObject() {
        String rendered = StoryPlanningPrompts.ROUTE;

        assertTrue(rendered.contains("\"requiresState\""), "提示词缺少 requiresState 字段说明");
        assertTrue(rendered.contains("\"changesState\""), "提示词缺少 changesState 字段说明");
        assertTrue(rendered.contains("LOCATION"), "提示词必须展开 StateChangeKind 枚举");
    }

    @Test
    void renderedRoutePromptMatchesCatalogTemplateAfterTokenResolution() {
        String template = PromptCatalog.contentOf("derive_plan", "system");
        String rendered = StoryPlanningPrompts.ROUTE;
        String resolved = resolveTemplate(template);

        assertTrue(rendered.startsWith(resolved),
                "渲染后的 ROUTE 提示词必须由 catalog 模板解析 token 后得到");
        assertFalse(rendered.contains("derive.route_narrative@2"),
                "旧路由解析器不允许提示词声明 ROUTE v2 契约");
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
