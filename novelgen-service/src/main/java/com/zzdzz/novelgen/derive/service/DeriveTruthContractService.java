package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zzdzz.novelgen.derive.contract.TruthContract;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一真相合同：真相卡、卷合同、章纲和 P6 只引用同一 truthId。
 *
 * <p>真相卡历史上有两种根形态：{@code {"truth": {...}}} 与直接的
 * {@code {"solution": {...}}}。两种形态都必须能归一，否则统一合同会静默退回旧脊柱，
 * 继续制造双轨真相。
 */
@Service
public class DeriveTruthContractService {

    public TruthResolution resolve(long runId, JsonNode spine, JsonNode truthCard,
                                   JsonNode volumeContracts) {
        JsonNode card = normalizeTruthCard(truthCard);
        String spineTruth = text(spine, "finalTruth");
        String cardTruth = text(card, "finalTruth");
        if (cardTruth.isBlank()) {
            cardTruth = composeSolutionTruth(card);
        }
        String spineChoice = text(spine, "finalChoice");
        String cardChoice = text(card, "finalChoice");
        List<String> conflicts = new ArrayList<>();
        if (truthConflicts(spineTruth, cardTruth)) {
            conflicts.add("真相卡与故事脊柱的案情不一致");
        }
        if (truthConflicts(spineChoice, cardChoice)) {
            conflicts.add("真相卡与故事脊柱的最终选择不一致");
        }
        String finalTruth = !cardTruth.isBlank() ? cardTruth : spineTruth;
        String finalChoice;
        if (!cardChoice.isBlank()) {
            finalChoice = cardChoice;
        } else if (!cardTruth.isBlank()) {
            finalChoice = "按唯一真相合同完成最终选择：" + cardTruth;
        } else {
            finalChoice = spineChoice;
        }
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        appendTexts(card, forbidden, "forbidden", "forbiddenReveals");
        appendTexts(spine, forbidden, "forbidden", "forbiddenReveals");
        appendTexts(volumeContracts, forbidden, "forbidden", "forbiddenReveals");
        LinkedHashSet<String> threads = new LinkedHashSet<>();
        appendTexts(card, threads, "evidenceThreads", "requiredThreads", "threadKey");
        appendTexts(spine, threads, "evidenceThreads", "requiredThreads", "threadKey");
        appendTexts(volumeContracts, threads, "evidenceThreads", "requiredThreads", "threadKey");
        String truthId = "T-" + Integer.toHexString(
                (runId + ":" + finalTruth + ":" + finalChoice).hashCode());
        return new TruthResolution(new TruthContract(truthId, finalTruth, finalChoice,
                new ArrayList<>(forbidden), new ArrayList<>(threads)), conflicts);
    }

    /** 兼容旧嵌套形态与现网真相卡根形态。 */
    static JsonNode normalizeTruthCard(JsonNode truthCard) {
        if (truthCard == null || truthCard.isMissingNode() || truthCard.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        JsonNode nested = truthCard.path("truth");
        return nested.isObject() ? nested : truthCard;
    }

    /** 真相卡只有 solution 时，拼出可供统一合同比较的完整案情。 */
    static String composeSolutionTruth(JsonNode card) {
        JsonNode solution = card == null ? JsonNodeFactory.instance.nullNode() : card.path("solution");
        if (!solution.isObject()) {
            return "";
        }
        String culprit = text(solution, "culprit");
        String motive = text(solution, "motive");
        if (motive.isBlank()) {
            motive = text(solution, "mote");
        }
        String method = text(solution, "method");
        if (culprit.isBlank() && motive.isBlank() && method.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!culprit.isBlank()) {
            out.append("作案者：").append(culprit);
        }
        if (!motive.isBlank()) {
            if (!out.isEmpty()) {
                out.append("；");
            }
            out.append("动机：").append(motive);
        }
        if (!method.isBlank()) {
            if (!out.isEmpty()) {
                out.append("；");
            }
            out.append("手法：").append(method);
        }
        return out.toString();
    }

    /**
     * 文本完全相等视为一致；不同措辞用二元字符组重合度判断，避免把同一案情的
     * 不同表述误报成冲突，同时能识别“苏晚失踪案 vs 珍品骗局”这类完全不同案情。
     */
    static boolean truthConflicts(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        String a = normalizeTruth(left);
        String b = normalizeTruth(right);
        if (a.equals(b)) {
            return false;
        }
        return shingleSimilarity(a, b) < 0.12;
    }

    private static String normalizeTruth(String value) {
        return value.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase();
    }

    private static double shingleSimilarity(String left, String right) {
        if (left.length() < 2 || right.length() < 2) {
            return left.equals(right) ? 1.0 : 0.0;
        }
        Set<String> a = shingles(left);
        Set<String> b = shingles(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> shingles(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            out.add(value.substring(i, i + 2));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node != null && !node.isMissingNode() && !node.isNull()
                && node.has(field) ? node.path(field).asText("") : "";
    }

    /** 只收集已知合同键，避免把 arc/trueAnswer 等叙述文本误当成线程 key。 */
    private static void appendTexts(JsonNode node, Collection<String> out, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        Set<String> wanted = Set.of(keys);
        if (node.isArray()) {
            node.forEach(item -> appendTexts(item, out, keys));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String key : wanted) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText("").isBlank()) {
                out.add(value.asText(""));
            } else if (value.isArray()) {
                value.forEach(item -> {
                    if (item.isTextual() && !item.asText("").isBlank()) {
                        out.add(item.asText(""));
                    } else {
                        appendTexts(item, out, keys);
                    }
                });
            }
        }
        for (String container : List.of("globalThreads", "truthLadder", "volumes")) {
            JsonNode child = node.path(container);
            if (!child.isMissingNode()) {
                appendTexts(child, out, keys);
            }
        }
    }

    public record TruthResolution(TruthContract contract, List<String> conflicts) {
    }
}