package com.zzdzz.novelgen.derive.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同 LLM 输出 → {@link StoryContract} 的纯解析器。
 *
 * <p>只做结构归一与键名兼容（camelCase / snake_case 两套都认），不做业务裁决；
 * 业务规则一律交给 {@link StoryContractValidator}，校验失败由调用方带原因喂回重试。
 * 解析失败抛 {@link IllegalArgumentException}（LlmJson 会按解析失败重试）。
 */
public final class StoryContractParser {

    private StoryContractParser() {
    }

    /** 解析合同 JSON；scope 提供 chapterCount/volumeCount 等规模边界。 */
    public static StoryContract parse(JsonNode root, PlanContracts.ScopeContract scope) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("合同输出必须是 JSON 对象");
        }
        if (scope == null) {
            throw new IllegalArgumentException("合同解析缺少规模合同 scope");
        }
        List<StoryContract.CharacterSlot> cast = parseCast(root.path("cast"));
        StoryContract.Truth truth = parseTruth(root.path("truth"), cast, scope);
        List<StoryContract.Clue> clues = parseClues(root.path("clues"), truth, scope);
        List<StoryContract.ThreadRef> threads = parseThreads(root.path("threads"), clues, scope);
        List<String> forbidden = parseForbidden(root.path("forbidden"));
        List<StoryContract.VolumeTruth> volumeTruth =
                parseVolumeTruth(root.path("volumeTruth"), truth, forbidden, scope);
        return new StoryContract(0L, 0, "FROZEN", cast, truth, clues, threads, forbidden,
                volumeTruth, scope);
    }

    private static List<StoryContract.CharacterSlot> parseCast(JsonNode arr) {
        List<StoryContract.CharacterSlot> out = new ArrayList<>();
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode node : arr) {
            if (!node.isObject()) {
                continue;
            }
            String key = text(node, "key", "characterKey");
            String name = text(node, "name", "displayName");
            String role = text(node, "role", "function");
            String identity = text(node, "identity", "identityLine");
            if (identity.isBlank()) {
                identity = text(node, "roleLine", "description");
            }
            out.add(new StoryContract.CharacterSlot(key, name, role,
                    text(node, "gender", "sex"), identity));
        }
        return out;
    }

    private static StoryContract.Truth parseTruth(JsonNode node, List<StoryContract.CharacterSlot> cast,
                                                  PlanContracts.ScopeContract scope) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("合同缺少 truth 对象");
        }
        String culpritKey = text(node, "culpritKey", "culprit_key");
        String culpritName = text(node, "culpritName", "culprit");
        Map<String, String> keyByName = new LinkedHashMap<>();
        for (StoryContract.CharacterSlot slot : cast) {
            keyByName.put(slot.name(), slot.key());
        }
        if (culpritKey.isBlank() && !culpritName.isBlank()) {
            culpritKey = keyByName.getOrDefault(culpritName, "");
        }
        if (culpritName.isBlank() && !culpritKey.isBlank()) {
            for (StoryContract.CharacterSlot slot : cast) {
                if (culpritKey.equals(slot.key())) {
                    culpritName = slot.name();
                    break;
                }
            }
        }
        int reveal = node.path("revealChapter").isNumber() ? node.path("revealChapter").asInt()
                : node.path("reveal_chapter").asInt(0);
        if (reveal <= 0) {
            reveal = scope.chapterCount();
        }
        String finalTruth = text(node, "finalTruth", "final_truth");
        String finalChoice = text(node, "finalChoice", "final_choice");
        if (finalChoice.isBlank() && !finalTruth.isBlank()) {
            finalChoice = "按唯一真相合同完成最终选择：" + finalTruth;
        }
        return new StoryContract.Truth(culpritKey, culpritName,
                text(node, "motive", "motivation"), text(node, "method", "means"),
                finalTruth, finalChoice, reveal,
                text(node, "motiveTag", "motive_tag"),
                text(node, "methodTag", "method_tag"));
    }

    private static List<StoryContract.Clue> parseClues(JsonNode arr, StoryContract.Truth truth,
                                                       PlanContracts.ScopeContract scope) {
        List<StoryContract.Clue> out = new ArrayList<>();
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode node : arr) {
            if (!node.isObject()) {
                continue;
            }
            String clueKey = text(node, "clueKey", "clue_key", "clueId", "id");
            String clue = text(node, "clue", "text", "observation");
            int planted = intOf(node, 0, "plantedChapter", "planted_chapter", "chapter");
            int payoff = intOf(node, 0, "payoffChapter", "payoff_chapter");
            if (payoff <= 0) {
                payoff = truth.revealChapter() > planted ? truth.revealChapter() : planted + 1;
            }
            if (payoff > scope.chapterCount()) {
                payoff = scope.chapterCount();
            }
            out.add(new StoryContract.Clue(clueKey, clue, planted, payoff,
                    text(node, "ownerKey", "owner_key", "owner")));
        }
        return out;
    }

    private static List<StoryContract.ThreadRef> parseThreads(JsonNode arr,
                                                              List<StoryContract.Clue> clues,
                                                              PlanContracts.ScopeContract scope) {
        List<StoryContract.ThreadRef> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                if (!node.isObject()) {
                    continue;
                }
                String key = text(node, "threadKey", "thread_key", "key");
                int opened = intOf(node, 1, "openedVolume", "opened_volume");
                int payoff = intOf(node, scope.volumeCount(), "payoffVolume", "payoff_volume");
                out.add(new StoryContract.ThreadRef(key, text(node, "kind", "type"),
                        opened, payoff, text(node, "ownerKey", "owner_key", "owner")));
            }
        }
        if (out.isEmpty()) {
            for (StoryContract.Clue clue : clues) {
                out.add(new StoryContract.ThreadRef(clue.clueKey(), "CLUE", 1,
                        scope.volumeCount(), clue.ownerKey()));
            }
        }
        return out;
    }

    private static List<String> parseForbidden(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText("").isBlank()) {
                    out.add(item.asText("").trim());
                }
            }
        } else if (node.isTextual() && !node.asText("").isBlank()) {
            out.add(node.asText("").trim());
        }
        return out;
    }

    private static List<StoryContract.VolumeTruth> parseVolumeTruth(
            JsonNode arr, StoryContract.Truth truth, List<String> forbidden,
            PlanContracts.ScopeContract scope) {
        List<StoryContract.VolumeTruth> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                if (!node.isObject()) {
                    continue;
                }
                int volumeNo = intOf(node, 0, "volumeNo", "volume_no");
                String reveal = text(node, "reveal", "trueAnswer", "finalTruth");
                out.add(new StoryContract.VolumeTruth(volumeNo, reveal,
                        parseForbidden(node.path("forbidden"))));
            }
        }
        if (out.isEmpty() && scope.volumeCount() == 1 && !truth.finalTruth().isBlank()) {
            out.add(new StoryContract.VolumeTruth(1, truth.finalTruth(), List.copyOf(forbidden)));
        }
        return out;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual()) {
                String raw = value.asText("").trim();
                if (!raw.isEmpty()) {
                    return raw;
                }
            }
        }
        return "";
    }

    private static int intOf(JsonNode node, int fallback, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText("").trim());
                } catch (NumberFormatException ignored) {
                    // 继续尝试下一个别名键。
                }
            }
        }
        return fallback;
    }
}