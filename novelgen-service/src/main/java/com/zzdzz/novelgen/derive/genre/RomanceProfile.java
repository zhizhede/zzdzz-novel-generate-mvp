package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 言情类型插件：合同、义务和证据均使用 RomanceContract 独立模型。 */
@Component
public class RomanceProfile implements GenreProfile<RomanceContract> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String genre() {
        return "ROMANCE";
    }

    @Override
    public RomanceContract parseContract(JsonNode root, PlanContracts.ScopeContract scope) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("GENRE_CONTRACT_INVALID：言情合同必须是 JSON 对象");
        }
        try {
            RomanceContract parsed = MAPPER.treeToValue(root, RomanceContract.class);
            int version = parsed.version() > 0 ? parsed.version() : 1;
            return new RomanceContract(version, scope, parsed.cast(),
                    parsed.primaryRelationship(), parsed.stages(), parsed.forbidden());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("GENRE_CONTRACT_INVALID：" + e.getMessage(), e);
        }
    }

    @Override
    public List<StoryObligation> projectObligations(RomanceContract contract,
                                                    List<ChapterScheduleEntry> schedule) {
        if (contract == null) {
            return List.of();
        }
        List<StoryObligation> out = new ArrayList<>();
        for (RomanceStageKind kind : RomanceStageKind.values()) {
            RomanceStage stage = contract.stages().get(kind);
            if (stage == null) {
                continue;
            }
            String obligationKind = switch (kind) {
                case RELATIONSHIP_START -> "ADVANCE_THREAD";
                case MISUNDERSTANDING -> "CHARACTER_CHOICE";
                case ATTACHMENT_ESCALATION -> "CHANGE_STATE";
                case CONFLICT_PEAK -> "CHARACTER_COST";
                case CONFESSION -> "PAYOFF_THREAD";
                case RECONCILIATION -> "LOCAL_CLOSURE";
                case RELATIONSHIP_CLOSURE -> "FINAL_CLOSURE";
            };
            String targetKey = switch (kind) {
                case MISUNDERSTANDING, CONFLICT_PEAK -> stage.targetKey();
                default -> contract.primaryRelationship().threadKey();
            };
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", kind.name());
            payload.put("threadKey", contract.primaryRelationship().threadKey());
            payload.put("actorKeys", stage.actorKeys());
            payload.put("proof", stage.proof());
            payload.put("relationshipKind", contract.primaryRelationship().kind());
            out.add(new StoryObligation(
                    StoryObligation.obligationKey(stage.chapterNo(), obligationKind, targetKey),
                    stage.chapterNo(), obligationKind, targetKey, stage.required(), false,
                    "HARD", "ROMANCE_CONTRACT", payload));
        }
        return List.copyOf(out);
    }

    @Override
    public List<PlanContracts.PlanIssue> validateContract(RomanceContract contract) {
        return List.of();
    }

    @Override
    public List<PlanContracts.PlanIssue> auditGate(RomanceContract contract, StoryKernel kernel,
                                                   StoryEvidenceIndex evidence) {
        return new RomanceGateAuditor().audit(contract, kernel, evidence);
    }

    @Override
    public List<PlanContracts.PlanIssue> auditQc(RomanceContract contract, StoryKernel kernel,
                                                  StoryEvidenceIndex evidence,
                                                  GenreQcAuditor.CompiledDraft draft) {
        return new RomanceQcAuditor().auditQc(contract, kernel, evidence, draft);
    }
}
