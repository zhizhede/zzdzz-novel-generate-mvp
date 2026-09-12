package com.zzdzz.novelgen.dao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 素材卡数据访问：设定层实体卡的 CRUD 与注入查询。 */
@Repository
public class MaterialCardDAO {

    private static final RowMapper<MaterialCardDO> MAPPER;

    static {
        ObjectMapper mapper = new ObjectMapper();
        RowMapper<MaterialCardDO> rowMapper = (rs, i) -> {
            List<String> aliases;
            try {
                aliases = mapper.readValue(rs.getString("aliases"), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                aliases = List.of();
            }
            return new MaterialCardDO(rs.getLong("id"), rs.getLong("novel_id"), rs.getString("kind"),
                    rs.getString("name"), aliases, rs.getString("summary"), rs.getString("content_md"),
                    rs.getBoolean("pinned"), rs.getString("status"),
                    (Integer) rs.getObject("source_chapter"), rs.getBoolean("is_deleted"));
        };
        MAPPER = rowMapper;
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MaterialCardDAO(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<MaterialCardDO> listByNovel(long novelId, String kind) {
        return jdbc.query("""
                SELECT * FROM material_cards WHERE novel_id=? AND is_deleted=false
                  AND (?::text IS NULL OR kind=?)
                ORDER BY kind, name
                """, MAPPER, novelId, kind, kind);
    }

    public MaterialCardDO findById(long id) {
        List<MaterialCardDO> rows = jdbc.query(
                "SELECT * FROM material_cards WHERE id=? AND is_deleted=false", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean exists(long novelId, String kind, String name) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM material_cards
                WHERE novel_id=? AND kind=? AND name=? AND is_deleted=false
                """, Long.class, novelId, kind, name);
        return count != null && count > 0;
    }

    public void insert(long novelId, String kind, String name, List<String> aliases, String summary,
                       String contentMd, boolean pinned, String status, Integer sourceChapter) {
        jdbc.update("""
                INSERT INTO material_cards (novel_id, kind, name, aliases, summary, content_md, pinned, status, source_chapter)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, novelId, kind, name, toJson(aliases), summary, contentMd, pinned, status, sourceChapter);
    }

    public void update(long id, String name, List<String> aliases, String summary, String contentMd,
                       Boolean pinned, String status, Integer sourceChapter) {
        jdbc.update("""
                UPDATE material_cards SET name=?, aliases=?::jsonb, summary=?, content_md=?, pinned=?,
                       status=?, source_chapter=?, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, name, toJson(aliases), summary, contentMd, pinned, status, sourceChapter, id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE material_cards SET is_deleted=true, delete_time=NOW() WHERE id=? AND is_deleted=false", id);
    }

    /** 作品是否有任何素材卡（注入降级判断：无卡回退 canon 整文档）。 */
    public boolean hasCards(long novelId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_cards WHERE novel_id=? AND is_deleted=false", Long.class, novelId);
        return count != null && count > 0;
    }

    private String toJson(List<String> aliases) {
        try {
            return mapper.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (Exception e) {
            return "[]";
        }
    }
}
