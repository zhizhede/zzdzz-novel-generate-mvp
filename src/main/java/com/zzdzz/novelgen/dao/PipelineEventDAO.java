package com.zzdzz.novelgen.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 管线事件流水数据访问（SSE 事件的落库副本，日志页按章回放生成履历）。 */
@Repository
public class PipelineEventDAO {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PipelineEventDAO(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(Long novelId, Integer chapterNo, String stage, String phase, Object payload) {
        try {
            jdbc.update("""
                    INSERT INTO pipeline_events (novel_id, chapter_no, stage, phase, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, novelId, chapterNo, stage, phase, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            // 事件落库失败不阻断管线（SSE 已先行推送）
        }
    }

    /** 事件行：payload 保留原始 JSON 文本，前端展开查看。 */
    public record EventRow(long id, Integer chapterNo, String stage, String phase,
                           String payloadJson, String createTime) {
    }

    public List<EventRow> list(Long novelId, Integer chapterNo, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, chapter_no, stage, phase, payload::text, create_time FROM pipeline_events
                WHERE is_deleted = FALSE AND novel_id = ?
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(novelId);
        if (chapterNo != null) {
            sql.append(" AND chapter_no = ?");
            args.add(chapterNo);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new EventRow(rs.getLong(1),
                rs.getObject(2, Integer.class), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, java.time.OffsetDateTime.class)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))),
                args.toArray());
    }
}
