package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 章摘要（事实账）数据访问：后续章节前情的主要来源。 */
@Repository
public class DigestDAO {

    private final JdbcTemplate jdbc;

    public DigestDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
