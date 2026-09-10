package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** canon 文档（世界观/人物卡）数据访问。 */
@Repository
public class CanonDocDAO {

    private final JdbcTemplate jdbc;

    public CanonDocDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long novelId, String kind, String name) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM canon_docs WHERE novel_id=? AND kind=? AND name=? AND is_deleted=false",
                Long.class, novelId, kind, name);
        return count != null && count > 0;
    }

    public void insert(long novelId, String kind, String name, String content) {
        jdbc.update("INSERT INTO canon_docs (novel_id, kind, name, content) VALUES (?, ?, ?, ?)",
                novelId, kind, name, content);
    }

    public String findFirstByKind(long novelId, String kind) {
        return jdbc.queryForObject("""
                SELECT content FROM canon_docs
                WHERE novel_id=? AND kind=? AND is_deleted=false LIMIT 1
                """, String.class, novelId, kind);
    }
}
