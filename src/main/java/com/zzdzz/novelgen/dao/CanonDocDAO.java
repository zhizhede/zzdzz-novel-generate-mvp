package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.CanonDocDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** canon 文档（世界观/人物卡）数据访问。 */
@Repository
public class CanonDocDAO {

    private static final RowMapper<CanonDocDO> MAPPER = (rs, i) -> new CanonDocDO(
            rs.getLong("id"), rs.getLong("novel_id"), rs.getString("kind"), rs.getString("name"),
            rs.getString("content"), rs.getInt("sort_no"), rs.getBoolean("is_deleted"));

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

    /** 素材库列表：content 置 null 不取正文。 */
    public List<CanonDocDO> listByNovel(long novelId) {
        return jdbc.query("""
                SELECT id, novel_id, kind, name, NULL AS content, sort_no, is_deleted
                FROM canon_docs WHERE novel_id=? AND is_deleted=false ORDER BY kind, sort_no, id
                """, MAPPER, novelId);
    }

    public CanonDocDO findById(long id) {
        List<CanonDocDO> rows = jdbc.query("""
                SELECT id, novel_id, kind, name, content, sort_no, is_deleted
                FROM canon_docs WHERE id=? AND is_deleted=false
                """, MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateContent(long id, String content) {
        jdbc.update("UPDATE canon_docs SET content=?, update_time=NOW() WHERE id=? AND is_deleted=false",
                content, id);
    }
}
