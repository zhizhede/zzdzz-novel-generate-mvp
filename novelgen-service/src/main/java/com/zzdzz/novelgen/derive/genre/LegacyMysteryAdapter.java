package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractParser;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEntity;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligationProjector;
import com.zzdzz.novelgen.derive.story.StoryThread;
import com.zzdzz.novelgen.model.entity.derive.RunDO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 旧随机衍生 run 的悬疑合同兼容适配器：旧 run 没有 genre 列时固定按 MYSTERY 读取。
 */
public final class LegacyMysteryAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LegacyMysteryAdapter() {
    }

    /** 旧 run 类型读取：空 genre 一律兼容为 MYSTERY。 */
    public static String genre(RunDO run) {
        return run == null || run.getGenre() == null || run.getGenre().isBlank()
                ? "MYSTERY" : run.getGenre();
    }

    /** 从 step_status.steps.STORY_CONTRACT 读取旧合同快照。 */
    public static StoryContract toStoryContract(RunDO run) {
        if (run == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "LEGACY_COMPAT_ERROR：缺少 run");
        }
        JsonNode root = readTree(run.getStepStatus()).path("steps").path("STORY_CONTRACT");
        if (!root.isObject()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "LEGACY_COMPAT_ERROR：旧 run 缺少 STORY_CONTRACT 快照");
        }
        PlanContracts.ScopeContract scope = scope(root.path("scope"));
        try {
            return StoryContractParser.parse(root, scope);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "LEGACY_COMPAT_ERROR：" + e.getMessage());
        }
    }

    /** 旧合同 + 旧日程投影为通用故事核心。 */
    public static StoryKernel toKernel(StoryContract contract,
                                       List<ChapterScheduleEntry> schedule) {
        if (contract == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "LEGACY_COMPAT_ERROR：合同为空");
        }
        List<StoryEntity> entities = new ArrayList<>();
        for (StoryContract.CharacterSlot slot : contract.cast()) {
            entities.add(new StoryEntity(slot.key(), "CHARACTER", slot.name(), Map.of(), 1));
        }
        List<StoryThread> threads = new ArrayList<>();
        for (StoryContract.ThreadRef ref : contract.threads()) {
            threads.add(new StoryThread(ref.threadKey(), ref.kind(), ref.threadKey(),
                    null, "OPEN", ref.ownerKey(), null, Map.of()));
        }
        var obligations = StoryObligationProjector.project(new MysteryProfile(), contract,
                schedule == null ? List.of() : schedule, List.of(), List.of());
        return new StoryKernel(0L, 1, "LEGACY", contract.scope(), entities, List.of(), List.of(),
                threads, List.of(), obligations, List.of(), List.of());
    }

    private static PlanContracts.ScopeContract scope(JsonNode node) {
        int chapterCount = node.path("chapterCount").asInt(0);
        int volumeCount = node.path("volumeCount").asInt(1);
        if (chapterCount <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "LEGACY_COMPAT_ERROR：旧合同缺少 chapterCount");
        }
        PlanContracts.ScopeDecision decision = volumeCount > 1
                ? PlanContracts.ScopeDecision.MULTI_VOLUME
                : PlanContracts.ScopeDecision.SINGLE_VOLUME;
        return new PlanContracts.ScopeContract(decision, volumeCount, chapterCount,
                node.path("chapterAvg").asInt(chapterCount), List.of());
    }

    private static JsonNode readTree(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? node : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
