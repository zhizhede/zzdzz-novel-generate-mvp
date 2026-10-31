package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.derive.story.StoryEvidenceIndex;
import com.zzdzz.novelgen.derive.story.StoryKernel;
import com.zzdzz.novelgen.derive.story.StoryObligation;
import com.zzdzz.novelgen.derive.story.StoryObligationEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RomanceProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void romanceContractHasNoMysteryFields() throws Exception {
        RomanceContract contract = parse(contractJson());

        assertFalse(MAPPER.writeValueAsString(contract).contains("culprit"));
        assertFalse(MAPPER.writeValueAsString(contract).contains("motive"));
        assertFalse(MAPPER.writeValueAsString(contract).contains("method"));
        assertFalse(MAPPER.writeValueAsString(contract).contains("revealChapter"));
        assertFalse(MAPPER.writeValueAsString(contract).contains("clue"));
    }

    @Test
    void romanceCodecRoundTripsJsonShape() throws Exception {
        RomanceContract contract = parse(contractJson());
        RomanceCodec codec = new RomanceCodec();
        RomanceContract decoded = codec.decode(codec.encode(contract), contract.scope());

        assertEquals(contract.cast(), decoded.cast());
        assertEquals(contract.primaryRelationship(), decoded.primaryRelationship());
        assertEquals(contract.stages(), decoded.stages());
    }

    @Test
    void romanceRejectsMissingStage() {
        JsonNode root = contractJson();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("stages"))
                .remove("CONFESSION");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(root));

        assertTrue(error.getMessage().contains("missingStage"));
    }

    @Test
    void romanceRejectsOutOfOrderStage() {
        JsonNode root = contractJson();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("stages").path("CONFESSION"))
                .put("chapterNo", 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(root));

        assertTrue(error.getMessage().contains("单调")
                || error.getMessage().contains("CONFESSION"));
    }

    @Test
    void romanceRejectsUnknownActor() {
        JsonNode root = contractJson();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("stages").path("CONFESSION"))
                .putArray("actorKeys").add("C9");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(root));

        assertTrue(error.getMessage().contains("actorKeys"));
    }

    @Test
    void romanceRejectsThreadKeyMismatch() {
        JsonNode root = contractJson();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("primaryRelationship"))
                .put("threadKey", "REL-C1-C9");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(root));

        assertTrue(error.getMessage().contains("threadKey"));
    }

    @Test
    void romanceProjectsRelationshipObligations() {
        RomanceContract contract = parse(contractJson());

        List<StoryObligation> obligations = new RomanceProfile()
                .projectObligations(contract, schedule());

        assertTrue(has(obligations, 1, "ADVANCE_THREAD", "REL-C1-C2"));
        assertTrue(has(obligations, 2, "CHARACTER_CHOICE", "C2"));
        assertTrue(has(obligations, 3, "CHANGE_STATE", "REL-C1-C2"));
        assertTrue(has(obligations, 4, "CHARACTER_COST", "C1"));
        assertTrue(has(obligations, 5, "PAYOFF_THREAD", "REL-C1-C2"));
        assertTrue(has(obligations, 6, "LOCAL_CLOSURE", "REL-C1-C2"));
        assertTrue(has(obligations, 7, "FINAL_CLOSURE", "REL-C1-C2"));
        assertTrue(obligations.stream().allMatch(item ->
                item.payload().containsKey("stage")
                        && item.payload().containsKey("threadKey")
                        && item.payload().containsKey("actorKeys")
                        && item.payload().containsKey("proof")
                        && item.payload().containsKey("relationshipKind")));
    }

    @Test
    void romanceRejectsEarlyReconciliation() {
        JsonNode root = contractJson();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("stages").path("RECONCILIATION"))
                .put("chapterNo", 3);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(root));

        assertTrue(error.getMessage().contains("RECONCILIATION"));
    }

    @Test
    void romanceGateAcceptsClosedRelationship() {
        RomanceContract contract = parse(contractJson());
        StoryEvidenceIndex.Builder evidence = StoryEvidenceIndex.builder();
        for (var obligation : new RomanceProfile().projectObligations(contract, List.of())) {
            evidence.addObligationEvidence(new StoryObligationEvidence(
                    obligation.obligationKey(), obligation.chapterNo(), "EVENT",
                    "EV" + obligation.chapterNo(), null, List.of(), List.of(), "",
                    "BATCH", 1.0));
        }
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", contract.scope(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                evidence.build());

        assertTrue(new RomanceGateAuditor().audit(contract, kernel, evidence.build()).isEmpty());
    }

    @Test
    void romanceQcRejectsMissingConfession() {
        RomanceContract contract = parse(contractJson());
        StoryEvidenceIndex empty = StoryEvidenceIndex.builder().build();
        StoryKernel kernel = new StoryKernel(1L, 1, "ACTIVE", contract.scope(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                empty);

        assertTrue(hasCode(new RomanceQcAuditor().auditQc(contract, kernel, empty,
                new GenreQcAuditor.CompiledDraft(List.of(), Map.of())),
                "ROMANCE_QC_MISSING_EVIDENCE"));
    }

    private static RomanceContract parse(JsonNode root) {
        return new RomanceProfile().parseContract(root, scope());
    }

    private static boolean has(List<StoryObligation> obligations, int chapterNo, String kind,
                               String target) {
        return obligations.stream().anyMatch(item -> item.chapterNo() == chapterNo
                && kind.equals(item.kind()) && target.equals(item.targetKey()));
    }

    private static boolean hasCode(List<PlanContracts.PlanIssue> issues, String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static List<ChapterScheduleEntry> schedule() {
        return List.of(
                new ChapterScheduleEntry(1, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(2, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(3, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(4, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(5, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(6, 1, false, List.of(), List.of()),
                new ChapterScheduleEntry(7, 1, true, List.of(), List.of()));
    }

    private static PlanContracts.ScopeContract scope() {
        return new PlanContracts.ScopeContract(PlanContracts.ScopeDecision.SINGLE_VOLUME,
                1, 7, 2000, List.of("言情"));
    }

    private static JsonNode contractJson() {
        try {
            return MAPPER.readTree("""
                    {
                      "cast": [
                        {"key":"C1","name":"林晚","role":"PROTAGONIST","gender":"女","identity":"修复师"},
                        {"key":"C2","name":"沈砚","role":"LOVE_INTEREST","gender":"男","identity":"店主"}
                      ],
                      "primaryRelationship": {"threadKey":"REL-C1-C2","leftKey":"C1","rightKey":"C2","kind":"ROMANTIC","title":"修复师与店主"},
                      "stages": {
                        "RELATIONSHIP_START":{"chapterNo":1,"targetKey":"REL-C1-C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"开始","payload":{}},
                        "MISUNDERSTANDING":{"chapterNo":2,"targetKey":"C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"误会","payload":{}},
                        "ATTACHMENT_ESCALATION":{"chapterNo":3,"targetKey":"REL-C1-C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"升温","payload":{}},
                        "CONFLICT_PEAK":{"chapterNo":4,"targetKey":"C1","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"冲突","payload":{}},
                        "CONFESSION":{"chapterNo":5,"targetKey":"REL-C1-C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"告白","payload":{}},
                        "RECONCILIATION":{"chapterNo":6,"targetKey":"REL-C1-C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"和好","payload":{}},
                        "RELATIONSHIP_CLOSURE":{"chapterNo":7,"targetKey":"REL-C1-C2","actorKeys":["C1","C2"],"threadRefs":["REL-C1-C2"],"required":true,"proof":"闭合","payload":{}}
                      },
                      "forbidden": []
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
