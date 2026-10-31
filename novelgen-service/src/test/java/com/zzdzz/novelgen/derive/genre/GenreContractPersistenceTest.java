package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreContractDO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenreContractPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void genreContractPersistsJsonbPayload() throws IOException {
        DeriveGenreContractDO row = new DeriveGenreContractDO();
        row.setRunId(70L);
        row.setGenre("ROMANCE");
        row.setContractVersion(1);
        row.setScope("{}");
        row.setPayload("{}");
        row.setStatus("FROZEN");

        String xml = resource("mapper/DeriveGenreContractMapper.xml");

        assertTrue(xml.contains("INSERT INTO derive_genre_contract"));
        assertTrue(xml.contains("#{p.scope}::jsonb"));
        assertTrue(xml.contains("#{p.payload}::jsonb"));
        assertTrue(xml.contains("DELETE FROM derive_genre_contract"));
        assertTrue(xml.contains("WHERE run_id = #{runId}"));
    }

    @Test
    void genreContractRejectsUnknownGenre() {
        GenreContractCodecRegistry registry = new GenreContractCodecRegistry(
                List.of(new MysteryCodec(), new RomanceCodec()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.require("UNKNOWN"));

        assertTrue(error.getMessage().contains("Unknown genre codec"));
    }

    @Test
    void mysteryCodecRoundTripsLegacyStoryContract() throws Exception {
        StoryContract story = storyContract();
        MysteryCodec codec = new MysteryCodec();
        MysteryContract decoded = codec.decode(codec.encode(new MysteryContract(story)),
                story.scope());

        assertEquals(story.cast(), decoded.story().cast());
        assertEquals(story.clues(), decoded.story().clues());
        assertEquals(story.truth().culpritKey(), decoded.story().truth().culpritKey());
    }

    @Test
    void romanceCodecDoesNotCreateMysteryFields() throws Exception {
        RomanceContract contract = romanceContract();
        RomanceCodec codec = new RomanceCodec();
        JsonNode encoded = codec.encode(contract);

        assertFalse(encoded.has("truth"));
        assertFalse(encoded.has("clues"));
        assertFalse(encoded.has("culprit"));
        assertFalse(encoded.has("motive"));
        assertFalse(encoded.has("method"));
        assertFalse(encoded.has("revealChapter"));
        assertEquals("REL-C1-C2", encoded.path("primaryRelationship").path("threadKey").asText());
        assertEquals(7, encoded.path("stages").size());

        RomanceContract decoded = codec.decode(encoded, contract.scope());

        assertEquals(contract.cast(), decoded.cast());
        assertEquals(contract.primaryRelationship(), decoded.primaryRelationship());
        assertEquals(contract.stages().keySet(), decoded.stages().keySet());
    }

    private static StoryContract storyContract() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 7, 2000, List.of("测试"));
        return new StoryContract(70L, 1, "FROZEN",
                List.of(new StoryContract.CharacterSlot("C1", "林晚", "DETECTIVE", "女", "修复师"),
                        new StoryContract.CharacterSlot("C2", "沈砚", "ACCESS", "男", "店主")),
                new StoryContract.Truth("C2", "沈砚", "旧账", "换页", "真相", "选择", 7),
                List.of(new StoryContract.Clue("GM-CLUE-01", "纸屑", 1, 6, "C1")),
                List.of(new StoryContract.ThreadRef("GM-CLUE-01", "CLUE", 1, 1, "C1")),
                List.of(), List.of(), scope);
    }

    private static RomanceContract romanceContract() {
        PlanContracts.ScopeContract scope = new PlanContracts.ScopeContract(
                PlanContracts.ScopeDecision.SINGLE_VOLUME, 1, 7, 2000, List.of("言情"));
        RomanceRelationship relationship = new RomanceRelationship("REL-C1-C2", "C1", "C2",
                "ROMANTIC", "修复师与店主");
        Map<RomanceStageKind, RomanceStage> stages = new java.util.LinkedHashMap<>();
        stages.put(RomanceStageKind.RELATIONSHIP_START, stage(RomanceStageKind.RELATIONSHIP_START,
                1, relationship.threadKey(), List.of("C1", "C2"), "开始"));
        stages.put(RomanceStageKind.MISUNDERSTANDING, stage(RomanceStageKind.MISUNDERSTANDING,
                2, "C2", List.of("C1", "C2"), "误会"));
        stages.put(RomanceStageKind.ATTACHMENT_ESCALATION,
                stage(RomanceStageKind.ATTACHMENT_ESCALATION, 3, relationship.threadKey(),
                        List.of("C1", "C2"), "升温"));
        stages.put(RomanceStageKind.CONFLICT_PEAK, stage(RomanceStageKind.CONFLICT_PEAK,
                4, "C1", List.of("C1", "C2"), "冲突"));
        stages.put(RomanceStageKind.CONFESSION, stage(RomanceStageKind.CONFESSION,
                5, relationship.threadKey(), List.of("C1", "C2"), "告白"));
        stages.put(RomanceStageKind.RECONCILIATION, stage(RomanceStageKind.RECONCILIATION,
                6, relationship.threadKey(), List.of("C1", "C2"), "和好"));
        stages.put(RomanceStageKind.RELATIONSHIP_CLOSURE,
                stage(RomanceStageKind.RELATIONSHIP_CLOSURE, 7, relationship.threadKey(),
                        List.of("C1", "C2"), "闭合"));
        return new RomanceContract(1, scope, List.of(
                new RomanceCharacterSlot("C1", "林晚", "PROTAGONIST", "女", "修复师"),
                new RomanceCharacterSlot("C2", "沈砚", "LOVE_INTEREST", "男", "店主")),
                relationship, stages, List.of());
    }

    private static RomanceStage stage(RomanceStageKind kind, int chapterNo, String targetKey,
                                      List<String> actors, String proof) {
        return new RomanceStage(kind, chapterNo, targetKey, actors,
                List.of("REL-C1-C2"), true, proof, Map.of());
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = GenreContractPersistenceTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertTrue(input != null, "classpath 资源不存在：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
