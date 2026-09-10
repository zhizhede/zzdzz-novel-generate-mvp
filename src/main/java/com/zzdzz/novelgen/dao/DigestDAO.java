package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.DigestDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 章摘要（事实账）数据访问：后续章节前情的主要来源。 */
@Repository
public class DigestDAO {

    /** 素材库行：带章号（内部模型，非表行）。 */
    public record DigestItem(long id, int chapterNo, String contentMd, String facts, String updateTime) {
    }

    private static final RowMapper<DigestDO> MAPPER = (rs, i) -> new DigestDO(
            rs.getLong("id"), rs.getLong("chapter_id"), rs.getString("content_md"),
            rs.getString("facts"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public DigestDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 素材库：全作品事实账列表（时间正序）。 */
    public List<DigestItem> listByNovel(long novelId) {
        return jdbc.query("""
                SELECT d.id, c.chapter_no, d.content_md, d.facts::text AS facts, d.update_time
                FROM digests d JOIN chapters c ON c.id = d.chapter_id
                WHERE c.novel_id=? AND d.is_deleted=false
                ORDER BY c.chapter_no
                """, (rs, i) -> new DigestItem(rs.getLong("id"), rs.getInt("chapter_no"),
                rs.getString("content_md"), rs.getString("facts"),
                rs.getString("update_time")), novelId);
    }

    public void updateContent(long id, String contentMd, String factsJson) {
        jdbc.update("""
                UPDATE digests SET content_md=?, facts=?::jsonb, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, contentMd, factsJson, id);
    }

    /** 某章之前的最近 n 章摘要（时间正序） */
    public List<String> findRecent(long novelId, int beforeChapter, int n) {
        return jdbc.queryForList("""
                SELECT d.content_md FROM digests d
                JOIN chapters c ON c.id = d.chapter_id
                WHERE c.novel_id=? AND c.chapter_no < ? AND d.is_deleted=false
                ORDER BY c.chapter_no DESC LIMIT ?
                """, String.class, novelId, beforeChapter, n);
    }

    public void insert(long chapterId, String contentMd, String factsJson) {
        jdbc.update("INSERT INTO digests (chapter_id, content_md, facts) VALUES (?, ?, ?::jsonb)",
                chapterId, contentMd, factsJson);
    }

    public boolean existsByChapter(long chapterId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM digests WHERE chapter_id=? AND is_deleted=false",
                Integer.class, chapterId);
        return count != null && count > 0;
    }
}
