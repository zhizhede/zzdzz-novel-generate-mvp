package com.zzdzz.novelgen.derive.orchestration;

import com.zzdzz.novelgen.model.entity.derive.DeriveStageRunDO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V140 stage ledger 的表、约束与条件更新契约。 */
class DeriveStageRunMapperTest {

    @Test
    void v140LedgerIsRepeatableAndDurable() throws Exception {
        String sql = resource("/db/migration-derive/V140__derive_stage_run.sql");

        assertContains(sql, "CREATE TABLE IF NOT EXISTS derive_stage_run");
        assertContains(sql, "REFERENCES run (id) ON DELETE CASCADE");
        assertContains(sql, "UNIQUE (run_id, stage, attempt)");
        assertContains(sql, "CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')");
        assertContains(sql, "CHECK (stage IN ('DRAFT', 'FINAL_AUDIT', 'COMPLETE')");
        assertContains(sql, "lease_until");
        assertContains(sql, "error_code");
        assertContains(sql, "error_path");
        assertContains(sql, "output_revision");
        assertContains(sql, "WHERE status = 'RUNNING'");
    }

    @Test
    void mapperUsesReturningAndAtomicClaimFinish() throws Exception {
        String xml = resource("/mapper/DeriveStageRunMapper.xml");

        assertContains(xml, "<select id=\"insertFull\" resultType=\"long\">");
        assertContains(xml, "RETURNING id");
        assertContains(xml, "ON CONFLICT (run_id, stage, attempt) DO NOTHING");
        assertContains(xml, "status IN ('PENDING', 'FAILED')");
        assertContains(xml, "OR (status = 'RUNNING' AND lease_until &lt;= NOW())");
        assertContains(xml, "error_code = #{code}");
        assertContains(xml, "AND lease_owner = #{leaseOwner}");
        assertContains(xml, "error_path = #{path}");
        assertContains(xml, "output_revision = #{outputRevision,jdbcType=INTEGER}");
    }

    @Test
    void entityMapsAllDurableStageFields() throws Exception {
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getRunId"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getStage"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getStatus"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getAttempt"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getLeaseOwner"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getLeaseUntil"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getErrorCode"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getErrorPath"));
        assertNotNull(DeriveStageRunDO.class.getDeclaredMethod("getOutputRevision"));
    }

    private static String resource(String path) throws Exception {
        try (var in = DeriveStageRunMapperTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "missing: " + expected);
    }
}

