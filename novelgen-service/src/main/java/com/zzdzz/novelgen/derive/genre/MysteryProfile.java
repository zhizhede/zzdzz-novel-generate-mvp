package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractParser;
import com.zzdzz.novelgen.derive.contract.StoryContractValidator;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 悬疑类型插件：承载凶手、动机、手法、线索和揭示章等悬疑专属合同。
 */
@Component
public class MysteryProfile implements GenreProfile<MysteryContract> {

    @Override
    public String genre() {
        return "MYSTERY";
    }

    @Override
    public MysteryContract parseContract(JsonNode root, PlanContracts.ScopeContract scope) {
        StoryContract contract = StoryContractParser.parse(root, scope);
        List<String> errors = StoryContractValidator.validateForFreeze(contract, scope);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("CONTRACT_INVALID：" + String.join("；", errors));
        }
        return new MysteryContract(contract);
    }

    @Override
    public List<StoryObligation> projectObligations(MysteryContract wrapper,
                                                    List<ChapterScheduleEntry> schedule) {
        if (wrapper == null || wrapper.story() == null) {
            return List.of();
        }
        StoryContract contract = wrapper.story();
        List<StoryObligation> obligations = new ArrayList<>();
        for (StoryContract.Clue clue : contract.clues()) {
            if (clue.payoffChapter() <= clue.plantedChapter()) {
                throw new IllegalArgumentException("CONTRACT_INVALID：线索 " + clue.clueKey()
                        + " 的 payoffChapter=" + clue.payoffChapter()
                        + " 必须晚于 plantedChapter=" + clue.plantedChapter());
            }
            obligations.add(obligation(clue.plantedChapter(), "PLANT_THREAD", clue.clueKey(),
                    Map.of("clueKey", clue.clueKey(), "phase", "PLANT")));
            obligations.add(obligation(clue.payoffChapter(), "PAYOFF_THREAD", clue.clueKey(),
                    Map.of("clueKey", clue.clueKey(), "phase", "PAYOFF")));
        }
        if (contract.truth() != null && contract.truth().revealChapter() > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("closure", "TRUTH");
            payload.put("requiredActs", List.of("CULPRIT", "MOTIVE", "METHOD"));
            payload.put("requiredTags", List.of(contract.truth().culpritName(),
                    contract.truth().motiveTag(), contract.truth().methodTag()));
            payload.put("proofEventKeys", contract.clues().stream()
                    .map(StoryContract.Clue::clueKey).toList());
            payload.put("culpritKey", contract.truth().culpritKey());
            payload.put("culpritName", contract.truth().culpritName());
            payload.put("motiveTag", contract.truth().motiveTag());
            payload.put("methodTag", contract.truth().methodTag());
            payload.put("castKeys", contract.castKeys());
            obligations.add(obligation(contract.truth().revealChapter(), "FINAL_CLOSURE", "TRUTH",
                    payload));
        }
        return List.copyOf(obligations);
    }

    @Override
    public List<PlanContracts.PlanIssue> validateContract(MysteryContract wrapper) {
        StoryContract contract = wrapper == null ? null : wrapper.story();
        List<PlanContracts.PlanIssue> issues = new ArrayList<>();
        for (String error : StoryContractValidator.validateForFreeze(contract,
                contract == null ? null : contract.scope())) {
            String code = error.split("：", 2)[0];
            issues.add(new PlanContracts.PlanIssue("HARD", code,
                    PlanContracts.RepairScope.CONTRACT, null, null, List.of(), error));
        }
        return List.copyOf(issues);
    }

    private static StoryObligation obligation(int chapterNo, String kind, String targetKey,
                                              Map<String, Object> payload) {
        return new StoryObligation(StoryObligation.obligationKey(chapterNo, kind, targetKey),
                chapterNo, kind, targetKey, true, false, "HARD", "MYSTERY_CONTRACT", payload);
    }
}
