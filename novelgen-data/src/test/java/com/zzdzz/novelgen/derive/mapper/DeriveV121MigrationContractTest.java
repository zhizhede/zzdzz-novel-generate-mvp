package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zzdzz.novelgen.model.entity.derive.CharacterPlanDO;
import com.zzdzz.novelgen.model.entity.derive.StoryThreadDO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V121 故事规划合同的结构护栏。 */
class DeriveV121MigrationContractTest {

    @Test
    void v121DefinesStoryContractsWithoutDuplicatingWorldState() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration-derive/V121__derive_story_plan_contracts.sql")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("CREATE TABLE derive_character_plan"));
        assertTrue(sql.contains("CREATE TABLE derive_story_thread"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS contract JSONB"));
        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS plan_contract JSONB"));
        assertTrue(sql.contains("SYNTHETIC"));
        assertFalse(sql.contains("CREATE TABLE derive_character_state"));
    }

    @Test
    void newEntitiesUseExpectedTables() {
        assertEquals("derive_character_plan", CharacterPlanDO.class.getAnnotation(TableName.class).value());
        assertEquals("derive_story_thread", StoryThreadDO.class.getAnnotation(TableName.class).value());
    }

    @Test
    void v122WidensBeatSourceForSynthetic() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration-derive/V122__beat_source_synthetic.sql")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("ALTER COLUMN source TYPE VARCHAR(16)"));
        assertTrue(sql.contains("SYNTHETIC"));
    }

    @Test
    void beatUpdatesAreExplicitAndDoNotRewriteJsonb() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/mapper/DeriveBeatInstanceMapper.xml")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("<update id=\"updateFill\">"));
        assertTrue(sql.contains("<update id=\"updateStatus\">"));
        assertTrue(sql.contains("<update id=\"resetFilledByRun\">"));
        assertFalse(sql.contains("route_contract = #{filledText}"));
    }

    @Test
    void chapterUpdatesAreExplicitAndDoNotRewriteJsonb() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/mapper/DeriveChapterMapper.xml")) {
            assertNotNull(in);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("<update id=\"updateDraftOutline\">"));
        assertTrue(sql.contains("<update id=\"updateStatus\">"));
        assertTrue(sql.contains("<update id=\"updateContent\">"));
        assertFalse(sql.contains("plan_contract = #{draftOutline}"));
    }
}
