package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.plan.StoryPlanningPrompts;
import com.zzdzz.novelgen.llm.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合同提示词登记一致性（contract-first 计划 §2）：
 * derive_contract 必须在 PromptCatalog 登记且与 StoryPlanningPrompts.CONTRACT 逐字一致，
 * 否则库值优先的提示词页与代码模板会漂移。
 */
class ContractPromptArityTest {

    @Test
    void contractNodeIsRegisteredExactlyOnce() {
        List<PromptCatalog.TemplateDef> entries = PromptCatalog.ALL.stream()
                .filter(def -> "derive_contract".equals(def.node()))
                .toList();

        assertEquals(1, entries.size(), "derive_contract 必须且只能登记一条");
        assertEquals("system", entries.get(0).phase());
        assertTrue(entries.get(0).exact());
    }

    @Test
    void registeredContentMatchesCodeTemplate() {
        String catalog = PromptCatalog.contentOf("derive_contract", "system");

        assertNotNull(catalog, "PromptCatalog 缺 derive_contract/system 条目");
        assertEquals(StoryPlanningPrompts.CONTRACT, catalog,
                "PromptCatalog 与 StoryPlanningPrompts.CONTRACT 必须逐字一致");
        assertEquals("derive_contract", StoryPlanningPrompts.NODE_CONTRACT);
    }

    @Test
    void routePromptListsAllBeatRoles() {
        for (PlanContracts.BeatRole role : PlanContracts.BeatRole.values()) {
            assertTrue(StoryPlanningPrompts.ROUTE.contains(role.name()),
                    "ROUTE 提示词缺少 beatRole: " + role.name());
        }
    }

    @Test
    void routePromptListsAllStoryLayers() {
        for (PlanContracts.StoryLayer layer : PlanContracts.StoryLayer.values()) {
            assertTrue(StoryPlanningPrompts.ROUTE.contains(layer.name()),
                    "ROUTE 提示词缺少 storyLayer: " + layer.name());
        }
    }

    @Test
    void routePromptListsAllAppearanceModes() {
        for (PlanContracts.AppearanceMode mode : PlanContracts.AppearanceMode.values()) {
            assertTrue(StoryPlanningPrompts.ROUTE.contains(mode.name()),
                    "ROUTE 提示词缺少 appearanceMode: " + mode.name());
        }
    }

    @Test
    void routePromptListsAllStateAndThreadKinds() {
        for (PlanContracts.StateChangeKind kind : PlanContracts.StateChangeKind.values()) {
            assertTrue(StoryPlanningPrompts.ROUTE.contains(kind.name()),
                    "ROUTE 提示词缺少 StateChangeKind: " + kind.name());
        }
    }

    @Test
    void routePromptIsRegisteredExactlyOnceAndMatchesCodeTemplate() {
        List<PromptCatalog.TemplateDef> entries = PromptCatalog.ALL.stream()
                .filter(def -> "derive_plan".equals(def.node())
                        && "system".equals(def.phase()))
                .toList();

        assertEquals(1, entries.size(), "derive_plan/system 必须且只能登记一条");
        assertEquals(StoryPlanningPrompts.ROUTE_TEMPLATE, PromptCatalog.contentOf("derive_plan", "system"),
                "PromptCatalog 与 StoryPlanningPrompts.ROUTE 必须逐字一致");
    }

    @Test
    void routePromptMatchesLegacyRouteParserContract() {
        String route = StoryPlanningPrompts.ROUTE;

        assertTrue(route.contains("\"volumeNo\""), "ROUTE 必须要求旧路由解析器的 volumeNo");
        assertTrue(route.contains("\"chapters\""), "ROUTE 必须要求旧路由解析器的 chapters");
        assertTrue(route.contains("\"assignments\""), "ROUTE 必须要求旧路由解析器的 assignments");
        assertTrue(route.contains("\"syntheticSlots\""), "ROUTE 必须要求旧路由解析器的 syntheticSlots");
        assertFalse(route.contains("beatNarratives"),
                "ROUTE 不得混入 ROUTE v2 叙事契约，旧解析器无法消费该结构");
        assertFalse(route.contains("derive.route_narrative@2"),
                "ROUTE 不得同时声明两套输出契约");
    }

    @Test
    void contractTemplateHasNoFormatPlaceholders() {
        String catalog = PromptCatalog.contentOf("derive_contract", "system");

        assertNotNull(catalog);
        assertFalse(catalog.contains("%s"), "合同提示词不含运行时占位符");
        assertFalse(catalog.contains("%d"), "合同提示词不含运行时占位符");
        assertTrue(catalog.contains("GM-CLUE-01"), "示例必须给出线索 key 形态");
        assertTrue(catalog.contains("maxNamedCharacters"), "输入必须声明角色上限");
    }
}
