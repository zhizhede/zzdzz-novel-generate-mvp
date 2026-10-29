package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.derive.plan.PlanContracts;

import java.util.List;

/**
 * 冻结后的唯一故事合同（derive_story_contract 表的强类型视图）。
 *
 * <p>合同在 ⑥a 的 SCALE 之后、SPINE 之前生成并冻结，是 cast、唯一真相、线索、线程与禁令的
 * 唯一事实源：SPINE/VOLUMES/CHARACTERS/⑥b/P6/⑦/⑧ 只能引用它做细化，任何节点提出新的
 * culprit/motive/method、cast 姓名、线程 key 或线索体系都必须被确定性校验拒绝。
 *
 * <p>{@code runId/version/status} 由持久化层回填（解析阶段为 0/0/"FROZEN"）；
 * {@code volumeTruth} 是多卷书每卷的真相切片，必须在合同生成时一并冻结。
 */
public record StoryContract(
        long runId,
        int version,
        String status,
        List<CharacterSlot> cast,
        Truth truth,
        List<Clue> clues,
        List<ThreadRef> threads,
        List<String> forbidden,
        List<VolumeTruth> volumeTruth,
        PlanContracts.ScopeContract scope) {

    /** 有名角色上限：HEAD A 只允许合同里的这三个人。 */
    public static final int MAX_NAMED_CHARACTERS = 3;

    /** 角色槽位：key 固定 C1..C3，name 是全书唯一合法人名来源。 */
    public record CharacterSlot(String key, String name, String role, String gender, String identity) {
    }

    /** 唯一真相核：culprit 必须属于 cast，culpritName 必须等于该 cast 的 name。 */
    public record Truth(String culpritKey, String culpritName, String motive, String method,
                        String finalTruth, String finalChoice, int revealChapter,
                        String motiveTag, String methodTag) {

        /** 兼容旧合同与旧调用：未显式提供 tags 时读作空串，宽松 load 不因此失效。 */
        public Truth(String culpritKey, String culpritName, String motive, String method,
                     String finalTruth, String finalChoice, int revealChapter) {
            this(culpritKey, culpritName, motive, method, finalTruth, finalChoice,
                    revealChapter, "", "");
        }
    }

    /** 线索登记：埋设章与兑现章都在规模范围内，且兑现晚于埋设。 */
    public record Clue(String clueKey, String clue, int plantedChapter, int payoffChapter,
                       String ownerKey) {
    }

    /** 线程引用：threadKey 全书唯一，只能被下游原样引用。 */
    public record ThreadRef(String threadKey, String kind, int openedVolume, int payoffVolume,
                            String ownerKey) {
    }

    /** 每卷真相切片；多卷书必须在合同生成时冻结，下游只许细化不许改写。 */
    public record VolumeTruth(int volumeNo, String reveal, List<String> forbidden) {
    }

    /** 合同里某个角色槽位的姓名（按 key 取）。 */
    public String castName(String key) {
        if (key == null) {
            return "";
        }
        for (CharacterSlot slot : cast) {
            if (key.equals(slot.key())) {
                return slot.name();
            }
        }
        return "";
    }

    /** 合同角色 key 集合（按 C1..C3 顺序）。 */
    public List<String> castKeys() {
        return cast.stream().map(CharacterSlot::key).toList();
    }
}