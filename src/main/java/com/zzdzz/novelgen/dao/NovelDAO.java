package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.NovelDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 作品数据访问。 */
@Repository
public class NovelDAO {

    private static final RowMapper<NovelDO> MAPPER = (rs, i) -> new NovelDO(
            rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
            rs.getString("description"), (Long) rs.getObject("style_pack_id"),
            rs.getString("approval_mode"), rs.getString("status"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public NovelDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NovelDO> listAlive() {
        return jdbc.query("""
                SELECT id, user_id, title, description, style_pack_id, approval_mode, status, is_deleted
                FROM novels WHERE is_deleted=false ORDER BY id
                """, MAPPER);
    }

    public Long findIdByTitle(String title) {
        List<Long> rows = jdbc.query(
                "SELECT id FROM novels WHERE title=? AND is_deleted=false",
                (rs, i) -> rs.getLong(1), title);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(long userId, String title, String description, Long stylePackId, String approvalMode) {
        return jdbc.queryForObject(
                "INSERT INTO novels (user_id, title, description, style_pack_id, approval_mode) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, userId, title, description, stylePackId, approvalMode);
    }

    public String findApprovalMode(long novelId) {
        return jdbc.queryForObject(
                "SELECT approval_mode FROM novels WHERE id=?", String.class, novelId);
    }

    public void updateApprovalMode(long novelId, String mode) {
        jdbc.update("UPDATE novels SET approval_mode=?, update_time=NOW() WHERE id=? AND is_deleted=false",
                mode, novelId);
    }

    public int chapterCount(long novelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chapters WHERE novel_id=? AND is_deleted=false",
                Integer.class, novelId);
        return count == null ? 0 : count;
    }
}
