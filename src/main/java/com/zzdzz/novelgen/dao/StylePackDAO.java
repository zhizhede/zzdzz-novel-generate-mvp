package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 风格包数据访问：规则正文与指纹基线的唯一读写口。 */
@Repository
public class StylePackDAO {

    private final JdbcTemplate jdbc;

    public StylePackDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long findIdByName(String name) {
        List<Long> rows = jdbc.query(
                "SELECT id FROM style_packs WHERE name=? AND is_deleted=false",
                (rs, i) -> rs.getLong(1), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(String name, String description, String rulesMd, String fingerprint) {
        return jdbc.queryForObject(
                "INSERT INTO style_packs (name, description, rules_md, fingerprint) VALUES (?, ?, ?::text, ?::jsonb) RETURNING id",
                Long.class, name, description, rulesMd, fingerprint);
    }

    public void updateFingerprint(long id, String fingerprint) {
        jdbc.update("UPDATE style_packs SET fingerprint=?::jsonb, update_time=NOW() WHERE id=?",
                fingerprint, id);
    }

    /** 素材库人工修订：直改风格包规则正文（指纹不动，阈值仍由 fingerprint 驱动）。 */
    public void updateRulesMdByNovel(long novelId, String rulesMd) {
        jdbc.update("""
                UPDATE style_packs sp SET rules_md=?, update_time=NOW()
                FROM novels n WHERE n.style_pack_id = sp.id AND n.id = ?
                """, rulesMd, novelId);
    }

    public String findRulesMdByNovel(long novelId) {
        return jdbc.queryForObject("""
                SELECT sp.rules_md FROM style_packs sp
                JOIN novels n ON n.style_pack_id = sp.id WHERE n.id = ?
                """, String.class, novelId);
    }

    /** fingerprint 列原文（含 metrics/baseline 结构） */
    public String findFingerprintByNovel(long novelId) {
        return jdbc.queryForObject("""
                SELECT sp.fingerprint::text FROM style_packs sp
                JOIN novels n ON n.style_pack_id = sp.id WHERE n.id = ?
                """, String.class, novelId);
    }
}
