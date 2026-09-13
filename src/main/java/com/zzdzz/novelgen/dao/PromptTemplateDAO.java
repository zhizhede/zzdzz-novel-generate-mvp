package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.PromptTemplateDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** prompt_templates 提示词注册表访问：目录同步、人工编辑与查询。 */
@Repository
public class PromptTemplateDAO {

    private static final RowMapper<PromptTemplateDO> MAPPER = (rs, i) -> new PromptTemplateDO(
            rs.getLong("id"), rs.getString("node"), rs.getString("phase"), rs.getString("title"),
            rs.getString("content"), rs.getBoolean("exact"), rs.getInt("version"),
            rs.getBoolean("custom"), rs.getBoolean("enabled"), rs.getBoolean("is_deleted"),
            rs.getObject("update_time", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public PromptTemplateDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 目录同步：缺失才插入；未人工定制且代码模板变了（hash 不符）才更新并 version+1；custom 行永不覆盖。 */
    public void sync(String node, String phase, String title, String content, boolean exact, String catalogHash) {
        jdbc.update("""
                INSERT INTO prompt_templates (node, phase, title, content, exact, catalog_hash)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM prompt_templates WHERE node = ? AND phase = ? AND is_deleted = FALSE)
                """, node, phase, title, content, exact, catalogHash, node, phase);
        jdbc.update("""
                UPDATE prompt_templates SET title = ?, content = ?, exact = ?, catalog_hash = ?,
                       version = version + 1, update_time = NOW()
                WHERE node = ? AND phase = ? AND is_deleted = FALSE
                  AND custom = FALSE AND (catalog_hash IS DISTINCT FROM ? OR content <> ? OR title <> ?)
                """, title, content, exact, catalogHash, node, phase, catalogHash, content, title);
    }

    /** 人工编辑：content 覆盖、置 custom、version+1。 */
    public int updateContent(long id, String content) {
        return jdbc.update("""
                UPDATE prompt_templates SET content = ?, custom = TRUE, version = version + 1, update_time = NOW()
                WHERE id = ? AND is_deleted = FALSE AND exact = TRUE
                """, content, id);
    }

    /** 重置回代码目录版本：custom 清除、内容与 hash 对齐目录、version+1。返回被重置行的 node/phase，无则 null。 */
    public record Reset(String node, String phase) {}

    public Optional<Reset> findNodePhase(long id) {
        return jdbc.query("""
                SELECT node, phase FROM prompt_templates WHERE id = ? AND is_deleted = FALSE
                """, (rs, i) -> new Reset(rs.getString(1), rs.getString(2)), id).stream().findFirst();
    }

    public int reset(long id, String content, String catalogHash) {
        return jdbc.update("""
                UPDATE prompt_templates SET content = ?, catalog_hash = ?, custom = FALSE,
                       version = version + 1, update_time = NOW()
                WHERE id = ? AND is_deleted = FALSE
                """, content, catalogHash, id);
    }

    public List<PromptTemplateDO> findAll() {
        return jdbc.query("""
                SELECT * FROM prompt_templates WHERE is_deleted = FALSE ORDER BY node, phase
                """, MAPPER);
    }

    public Optional<PromptTemplateDO> findById(long id) {
        return jdbc.query("""
                SELECT * FROM prompt_templates WHERE id = ? AND is_deleted = FALSE
                """, MAPPER, id).stream().findFirst();
    }
}
