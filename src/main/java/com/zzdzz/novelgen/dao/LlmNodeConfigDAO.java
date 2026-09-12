package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.LlmNodeConfigDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** LLM 节点路由配置数据访问。 */
@Repository
public class LlmNodeConfigDAO {

    private static final RowMapper<LlmNodeConfigDO> MAPPER = (rs, i) -> new LlmNodeConfigDO(
            rs.getLong("id"), rs.getString("node"), rs.getString("model"),
            (Double) rs.getObject("temperature"), (Integer) rs.getObject("max_tokens"),
            rs.getString("extra_json"), rs.getBoolean("enabled"), rs.getString("remark"),
            rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public LlmNodeConfigDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 生效配置：node 命中且 enabled（MiniMaxClient 每次调用解析）。无行返回 null。 */
    public LlmNodeConfigDO findEnabled(String node) {
        List<LlmNodeConfigDO> rows = jdbc.query("""
                SELECT * FROM llm_node_config WHERE node=? AND is_deleted=false AND enabled=true
                """, MAPPER, node);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<LlmNodeConfigDO> listAll() {
        return jdbc.query("SELECT * FROM llm_node_config WHERE is_deleted=false ORDER BY id", MAPPER);
    }

    public LlmNodeConfigDO findById(long id) {
        List<LlmNodeConfigDO> rows = jdbc.query(
                "SELECT * FROM llm_node_config WHERE id=? AND is_deleted=false", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean exists(String node) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM llm_node_config WHERE node=? AND is_deleted=false", Long.class, node);
        return count != null && count > 0;
    }

    public long insert(String node, String model, Double temperature, Integer maxTokens,
                       String extraJson, boolean enabled, String remark) {
        Long id = jdbc.queryForObject("""
                INSERT INTO llm_node_config (node, model, temperature, max_tokens, extra_json, enabled, remark)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                """, Long.class, node, model, temperature, maxTokens, extraJson, enabled, remark);
        return id == null ? -1L : id;
    }

    public void update(long id, String model, Double temperature, Integer maxTokens,
                       String extraJson, boolean enabled, String remark) {
        jdbc.update("""
                UPDATE llm_node_config SET model=?, temperature=?, max_tokens=?, extra_json=?::jsonb,
                       enabled=?, remark=?, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, model, temperature, maxTokens, extraJson, enabled, remark, id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE llm_node_config SET is_deleted=true, delete_time=NOW() WHERE id=? AND is_deleted=false", id);
    }
}
