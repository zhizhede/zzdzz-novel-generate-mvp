package com.zzdzz.novelgen.derive.plan;

import com.baomidou.mybatisplus.annotation.TableName;
import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePlanSnapshotDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** V139 快照表、revision 列与 JSONB mapper 的静态契约。 */
class PlanSnapshotMapperTest {

    @Test
    void v139IsRepeatableAndBindsRevisionColumns() throws Exception {
        String sql = resource("/db/migration-derive/V139__derive_plan_snapshot.sql");

        assertContains(sql, "ALTER TABLE run ADD COLUMN IF NOT EXISTS active_plan_revision INTEGER");
        assertContains(sql, "ALTER TABLE chapters ADD COLUMN IF NOT EXISTS plan_revision INTEGER");
        assertContains(sql, "ALTER TABLE beat_instance ADD COLUMN IF NOT EXISTS plan_revision INTEGER");
        assertContains(sql, "CREATE TABLE IF NOT EXISTS derive_plan_snapshot");
        assertContains(sql, "UNIQUE (run_id, revision)");
        assertContains(sql, "CHECK (status IN ('DRAFT', 'ADMITTED', 'SUPERSEDED')");
        assertContains(sql, "WHERE status = 'ADMITTED'");
        assertContains(sql, "REFERENCES run (id) ON DELETE CASCADE");
        assertContains(sql, "graph JSONB NOT NULL");
        assertContains(sql, "admission_report JSONB");
    }

    @Test
    void snapshotAndRevisionEntitiesUseExpectedColumns() throws Exception {
        assertEquals("derive_plan_snapshot",
                DerivePlanSnapshotDO.class.getAnnotation(TableName.class).value());
        assertNotNull(RunDO.class.getDeclaredMethod("getActivePlanRevision"));
        assertNotNull(ChapterDO.class.getDeclaredMethod("getPlanRevision"));
        assertNotNull(BeatInstanceDO.class.getDeclaredMethod("getPlanRevision"));
        assertNotNull(DerivePlanSnapshotDO.class.getDeclaredMethod("getGraph"));
        assertNotNull(DerivePlanSnapshotDO.class.getDeclaredMethod("getAdmissionReport"));
    }

    @Test
    void snapshotMapperUsesReturningAndExplicitJsonb() throws Exception {
        String xml = resource("/mapper/DerivePlanSnapshotMapper.xml");
        String beatXml = resource("/mapper/DeriveBeatInstanceMapper.xml");

        assertContains(xml, "<select id=\"insertFull\" resultType=\"long\">");
        assertContains(xml, "RETURNING id");
        assertContains(xml, "#{p.graph}::jsonb");
        assertContains(xml, "#{p.admissionReport}::jsonb");
        assertContains(xml, "WHERE run_id = #{runId} AND revision = #{revision}");
        assertContains(xml, "status = 'DRAFT'");
        assertContains(xml, "UPDATE run");
        assertContains(beatXml, "plan_revision");
    }

    private static String resource(String path) throws Exception {
        try (var in = PlanSnapshotMapperTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("missing: " + expected);
        }
    }
}