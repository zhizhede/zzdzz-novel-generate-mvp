package com.zzdzz.novelgen.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 门禁报告数据访问（JSONB 结果，可重放审计）。 */
@Repository
public class GateReportDAO {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GateReportDAO(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(long chapterId, Long sceneId, String gateType, int round,
                       boolean passed, Object result) {
        try {
            jdbc.update("""
                    INSERT INTO gate_reports (chapter_id, scene_id, gate_type, round, passed, result)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, chapterId, sceneId, gateType, round, passed, mapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteByChapter(long chapterId) {
        jdbc.update("DELETE FROM gate_reports WHERE chapter_id=?", chapterId);
    }

    public String findLatestFailureJson(long chapterId) {
        return jdbc.queryForObject(
                "SELECT result::text FROM gate_reports WHERE chapter_id=? AND gate_type='mechanical' AND passed=false "
                + "ORDER BY id DESC LIMIT 1", String.class, chapterId);
    }

    /** 指定场景最新失败报告：场景重写意见必须对号入座。 */
    public String findLatestSceneFailureJson(long chapterId, long sceneId) {
        List<String> rows = jdbc.query("""
                SELECT result::text FROM gate_reports
                WHERE chapter_id=? AND scene_id=? AND gate_type='mechanical' AND passed=false
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), chapterId, sceneId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 章级最新报告行（passed/create_time 是列值，result 是 JSON 文本，供 service 组装 VO）。 */
    public record LatestChapterReport(boolean passed, java.time.OffsetDateTime createTime, String resultJson) {
    }

    public LatestChapterReport findLatestChapterReport(long chapterId) {
        List<LatestChapterReport> rows = jdbc.query("""
                SELECT passed, create_time, result::text FROM gate_reports
                WHERE chapter_id=? AND scene_id IS NULL AND gate_type='mechanical'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new LatestChapterReport(rs.getBoolean(1),
                rs.getObject(2, java.time.OffsetDateTime.class), rs.getString(3)), chapterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 章级最新 AI 审校报告（gate_type='ai_review'，与机械门禁查询互不干扰）。 */
    public record LatestReview(boolean passed, java.time.OffsetDateTime createTime, String resultJson) {
    }

    public LatestReview findLatestChapterReview(long chapterId) {
        List<LatestReview> rows = jdbc.query("""
                SELECT passed, create_time, result::text FROM gate_reports
                WHERE chapter_id=? AND scene_id IS NULL AND gate_type='ai_review'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new LatestReview(rs.getBoolean(1),
                rs.getObject(2, java.time.OffsetDateTime.class), rs.getString(3)), chapterId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
