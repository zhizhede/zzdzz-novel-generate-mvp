package com.zzdzz.novelgen.derive.plan.compiler;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 计划编译层共享的稳定 hash、错误码与确定性映射。 */
public final class PlanCompilerSupport {

    public static final String CONTRACT_DIGEST_MISMATCH = "PLAN_CONTRACT_DIGEST_MISMATCH";
    public static final String SCHEDULE_MISSING = "PLAN_SCHEDULE_MISSING";
    public static final String SCHEDULE_GAP = "PLAN_SCHEDULE_GAP";
    public static final String CHAPTER_GAP = "PLAN_CHAPTER_GAP";
    public static final String BEAT_DUPLICATE = "PLAN_BEAT_DUPLICATE";
    public static final String BEAT_UNASSIGNED = "PLAN_BEAT_UNASSIGNED";
    public static final String BEAT_OUTSIDE_CHAPTER = "PLAN_BEAT_OUTSIDE_CHAPTER";
    public static final String BEAT_DEPENDENCY_MISSING = "PLAN_BEAT_DEPENDENCY_MISSING";
    public static final String BEAT_DEPENDENCY_SKIPPED = "PLAN_BEAT_DEPENDENCY_SKIPPED";
    public static final String CLUE_PLANT_MISSING = "PLAN_CLUE_PLANT_MISSING";
    public static final String CLUE_PAYOFF_MISSING = "PLAN_CLUE_PAYOFF_MISSING";
    public static final String CLUE_STRAY = "PLAN_CLUE_STRAY";
    public static final String REVEAL_BOUNDARY = "PLAN_REVEAL_BOUNDARY";
    public static final String KNOWLEDGE_SOURCE_MISSING = "PLAN_KNOWLEDGE_SOURCE_MISSING";
    public static final String STATE_DEPENDENCY = "PLAN_STATE_DEPENDENCY";
    public static final String THREAD_OPEN = "PLAN_THREAD_OPEN";
    public static final String THREAD_PAYOFF_MISSING = "PLAN_THREAD_PAYOFF_MISSING";
    public static final String CLOSURE_MISSING = "PLAN_CLOSURE_MISSING";
    public static final String MOTIVE_MISSING = "PLAN_MOTIVE_MISSING";
    public static final String SCRIPT_TARGET_MISSING = "PLAN_SCRIPT_TARGET_MISSING";
    public static final String STYLE_SUGGESTION = "PLAN_STYLE_SUGGESTION";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private PlanCompilerSupport() {
    }

    /** 冻结合同的稳定 SHA-256；同内容必须得到同一 digest。 */
    public static String contractHash(StoryContract contract) {
        try {
            return sha256(MAPPER.writeValueAsString(contract));
        } catch (Exception e) {
            throw new IllegalArgumentException("合同无法序列化为稳定 digest", e);
        }
    }

    /** 生成计划结构问题。 */
    public static PlanContracts.PlanIssue issue(
            String level,
            String code,
            PlanContracts.RepairScope scope,
            Integer volumeNo,
            Integer chapterNo,
            List<Integer> seqs,
            String message) {
        return new PlanContracts.PlanIssue(level, code, scope, volumeNo, chapterNo,
                seqs == null ? List.of() : List.copyOf(seqs), message);
    }

    /** BLOCKER 级结构问题。 */
    public static PlanContracts.PlanIssue blocker(
            String code, PlanContracts.RepairScope scope, Integer volumeNo,
            Integer chapterNo, List<Integer> seqs, String message) {
        return issue("BLOCKER", code, scope, volumeNo, chapterNo, seqs, message);
    }

    /** ADVISORY 级非阻断建议。 */
    public static PlanContracts.PlanIssue advisory(
            String code, Integer volumeNo, Integer chapterNo, List<Integer> seqs,
            String message) {
        return issue("ADVISORY", code, PlanContracts.RepairScope.NONE,
                volumeNo, chapterNo, seqs, message);
    }

    /** 固定 role 到库内拍型枚举的确定性映射。 */
    public static String beatTypeForRole(PlanContracts.BeatRole role) {
        return switch (role) {
            case CLUE_PLANT, CASE_SETUP, HOOK, SUSPECT, FUNCTION_USE, FUNCTION_FAILURE -> "铺垫";
            case CLUE_PAYOFF, FINAL_REVEAL, FALSE_ANSWER -> "反转";
            case CHARACTER_CHOICE, COST -> "抉择";
            case LOCAL_CLOSURE, FINAL_CLOSURE -> "收束";
            case HIGHLIGHT, RELATION_CONFLICT -> "情绪";
            default -> "事件";
        };
    }

    /** 输入拍型到受控 BeatRole 的确定性映射。 */
    public static PlanContracts.BeatRole roleForBeatType(String beatType, String arcSlot) {
        if ("铺垫".equals(beatType)) {
            return PlanContracts.BeatRole.CASE_SETUP;
        }
        if ("反转".equals(beatType)) {
            return PlanContracts.BeatRole.FALSE_ANSWER;
        }
        if ("情绪".equals(beatType)) {
            return PlanContracts.BeatRole.HIGHLIGHT;
        }
        if ("抉择".equals(beatType)) {
            return PlanContracts.BeatRole.CHARACTER_CHOICE;
        }
        if ("收束".equals(beatType)) {
            return PlanContracts.BeatRole.LOCAL_CLOSURE;
        }
        if ("开头".equals(arcSlot)) {
            return PlanContracts.BeatRole.CASE_SETUP;
        }
        if ("结尾".equals(arcSlot)) {
            return PlanContracts.BeatRole.LOCAL_CLOSURE;
        }
        return PlanContracts.BeatRole.MAIN_ADVANCE;
    }

    /** 只用领域词判定红线候选；命中必须显式 skipped，不得静默丢弃。 */
    public static String conflictSkipReason(PlanGraphRecords.BeatCandidate candidate) {
        String text = (candidate.beatType() == null ? "" : candidate.beatType())
                + " " + (candidate.arcSlot() == null ? "" : candidate.arcSlot());
        if (containsAny(text, "尸体", "遗骸", "殡葬", "死亡", "死者", "命案", "凶杀")) {
            return "无死亡红线：候选包含死亡/命案语义";
        }
        if (containsAny(text, "诡异", "人形", "化形", "超自然", "怪谈", "灵异", "鬼", "异象")) {
            return "无超自然红线：候选包含超自然语义";
        }
        return "";
    }

    /** pacing 是否在库约束 0..1 内。 */
    public static BigDecimal validPacing(BigDecimal value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            return fallback;
        }
        return value;
    }

    static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
