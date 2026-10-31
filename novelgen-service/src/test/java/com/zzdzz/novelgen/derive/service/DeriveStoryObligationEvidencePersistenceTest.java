package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.mapper.DeriveStoryObligationEvidenceMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveStoryObligationEvidencePersistenceTest {

    @Test
    void evidenceRowCarriesJsonbArraysAndUniqueKey() {
        DeriveStoryObligationEvidenceDO row = new DeriveStoryObligationEvidenceDO();
        row.setRunId(65L);
        row.setObligationKey("CH2:PLANT_THREAD:GM-CLUE-01");
        row.setChapterNo(2);
        row.setEvidenceKind("BEAT");
        row.setEventKey("EV1");
        row.setBeatSeq(3);
        row.setEntityKeys("[\"C1\"]");
        row.setThreadKeys("[\"GM-CLUE-01\"]");
        row.setFact("纸屑出现");
        row.setSource("BATCH");
        row.setConfidence(0.9);

        assertEquals("[\"C1\"]", row.getEntityKeys());
        assertEquals("[\"GM-CLUE-01\"]", row.getThreadKeys());
        assertEquals(0.9, row.getConfidence());
        assertNotNull(DeriveStoryObligationEvidenceMapper.class);
    }

    @Test
    void evidenceMapperXmlUsesExplicitJsonbAndChapterOrder() throws IOException {
        String xml = resource("mapper/DeriveStoryObligationEvidenceMapper.xml");

        assertTrue(xml.contains("INSERT INTO derive_story_obligation_evidence"));
        assertTrue(xml.contains("entity_keys"));
        assertTrue(xml.contains("thread_keys"));
        assertTrue(xml.contains("#{p.entityKeys}::jsonb"));
        assertTrue(xml.contains("#{p.threadKeys}::jsonb"));
        assertTrue(xml.contains("DELETE FROM derive_story_obligation_evidence"));
        assertTrue(xml.contains("ORDER BY chapter_no, obligation_key, evidence_kind"));
    }

    @Test
    void evidenceMigrationDefinesCompositeUniqueConstraint() throws IOException {
        String sql = resource("db/migration-derive/V145__derive_story_obligation_evidence.sql");

        assertTrue(sql.contains("CREATE TABLE derive_story_obligation_evidence"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_derive_story_obligation_evidence"));
        assertTrue(sql.contains("run_id, obligation_key, evidence_kind, chapter_no"));
        assertTrue(sql.contains("COALESCE(event_key, '')"));
        assertTrue(sql.contains("COALESCE(beat_seq, -1)"));
        assertTrue(sql.contains("ON DELETE CASCADE"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = DeriveStoryObligationEvidencePersistenceTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "classpath 资源不存在：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
