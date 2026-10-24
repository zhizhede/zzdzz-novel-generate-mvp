package com.zzdzz.novelgen.derive.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** V123 卷合同长文本与级联删除合同。 */
class DeriveV123MigrationContractTest {

    @Test
    void volumeArcIsTextAndForeignKeysCascade() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration-derive/V123__derive_volume_plan_text_and_cascade.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("ALTER COLUMN arc TYPE TEXT"));
        assertTrue(sql.contains("FOREIGN KEY (run_id) REFERENCES run(id) ON DELETE CASCADE"));
        assertTrue(sql.contains(
                "FOREIGN KEY (novel_id) REFERENCES novels(id) ON DELETE CASCADE"));
    }
}
