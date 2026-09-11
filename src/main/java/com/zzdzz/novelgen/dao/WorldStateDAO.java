package com.zzdzz.novelgen.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 世界状态账数据访问：每章一份结构化快照（novel_id+chapter_no 唯一），digest 时 upsert。 */
@Repository
public class WorldStateDAO {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorldStateDAO(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(long novelId, int chapterNo, Object state) {
        try {
            jdbc.update("""
                    INSERT INTO world_states (novel_id, chapter_no, state)
                    VALUES (?, ?, ?::jsonb)
                    ON CONFLICT (novel_id, chapter_no) WHERE is_deleted = FALSE
                    DO UPDATE SET state = EXCLUDED.state, update_time = now()
                    """, novelId, chapterNo, mapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("世界状态写入失败", e);
        }
    }

    /** 生成第 N 章用：N 之前最近一份快照，无则 null。 */
    public String findLatestBefore(long novelId, int beforeChapter) {
        List<String> rows = jdbc.query("""
                SELECT state::text FROM world_states
                WHERE novel_id=? AND chapter_no < ? AND is_deleted = FALSE
                ORDER BY chapter_no DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), novelId, beforeChapter);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 素材库查看：按章列出（含人工编辑目标定位）。 */
    public record StateRow(int chapterNo, String stateJson, String updateTime) {
    }

    public List<StateRow> listByNovel(long novelId, int limit) {
        return jdbc.query("""
                SELECT chapter_no, state::text, update_time FROM world_states
                WHERE novel_id=? AND is_deleted = FALSE
                ORDER BY chapter_no DESC LIMIT ?
                """, (rs, i) -> new StateRow(rs.getInt(1), rs.getString(2),
                rs.getObject(3, java.time.OffsetDateTime.class)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
                novelId, limit);
    }

    /** 人工纠偏：直接改某章快照。 */
    public void updateByChapter(long novelId, int chapterNo, String stateJson) {
        int n = jdbc.update("""
                UPDATE world_states SET state = ?::jsonb, update_time = now()
                WHERE novel_id=? AND chapter_no=? AND is_deleted = FALSE
                """, stateJson, novelId, chapterNo);
        if (n == 0) {
            jdbc.update("""
                    INSERT INTO world_states (novel_id, chapter_no, state) VALUES (?, ?, ?::jsonb)
                    """, novelId, chapterNo, stateJson);
        }
    }
}
