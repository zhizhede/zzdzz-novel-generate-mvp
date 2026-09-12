package com.zzdzz.novelgen.dao;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** volume_reviews 卷级复盘报告访问：每卷保留最新一份。 */
@Repository
public class VolumeReviewDAO {

    private final JdbcTemplate jdbc;

    public VolumeReviewDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(long novelId, int volNo, JsonNode report) {
        jdbc.update("""
                INSERT INTO volume_reviews (novel_id, vol_no, report)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (novel_id, vol_no) WHERE is_deleted = FALSE
                DO UPDATE SET report = EXCLUDED.report, update_time = NOW()
                """, novelId, volNo, report.toString());
    }

    /** 最新报告；无则 null。 */
    public String findJson(long novelId, int volNo) {
        return jdbc.query("""
                SELECT report::text FROM volume_reviews
                WHERE novel_id = ? AND vol_no = ? AND is_deleted = FALSE
                """, rs -> rs.next() ? rs.getString(1) : null, novelId, volNo);
    }
}
