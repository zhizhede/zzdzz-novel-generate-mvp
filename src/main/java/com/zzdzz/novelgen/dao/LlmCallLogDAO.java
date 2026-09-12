package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** LLM 调用台账数据访问：每次 AI 调用一行（含失败），请求响应全量留档可重放。 */
@Repository
public class LlmCallLogDAO {

    /** 用量聚合行（非表行，DAO 内部模型）。 */
    public record Totals(long calls, long promptTokens, long completionTokens,
                         long totalTokens, long avgLatencyMs) {
    }

    /** 节点统计行（非表行）：按 node 聚合近 N 天用量。 */
    public record NodeStat(long calls, long totalTokens, long avgLatencyMs, String lastModel) {
    }

    /** 用量分组（非表行）：node×model×峰谷，计费在 service 按价目折算。 */
    public record UsageGroup(String node, String model, boolean peak, long calls,
                             long promptTokens, long cachedTokens, long completionTokens, long latencySum) {
    }

    private static final RowMapper<LlmCallLogDO> MAPPER = (rs, i) -> new LlmCallLogDO(
            rs.getLong("id"), rs.getString("node"),
            (Long) rs.getObject("novel_id"), (Long) rs.getObject("chapter_id"),
            rs.getString("model"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
            rs.getInt("total_tokens"), rs.getInt("latency_ms"), rs.getString("status"),
            rs.getString("error_msg"), rs.getString("reasoning_text"),
            rs.getString("request_json"), rs.getString("response_json"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public LlmCallLogDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String node, Long novelId, Long chapterId, String model,
                       int promptTokens, int cachedTokens, int completionTokens, int totalTokens,
                       long latencyMs, String status, String errorMsg, String reasoningText,
                       String requestJson, String responseJson) {
        Long id = jdbc.queryForObject("""
                INSERT INTO llm_call_log (node, novel_id, chapter_id, model, prompt_tokens, cached_tokens,
                                          completion_tokens, total_tokens, latency_ms, status, error_msg,
                                          reasoning_text, request_json, response_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text, ?::jsonb, ?::jsonb)
                RETURNING id
                """, Long.class, node, novelId, chapterId, model,
                promptTokens, cachedTokens, completionTokens, totalTokens, latencyMs, status, errorMsg,
                reasoningText, requestJson, responseJson);
        return id == null ? -1L : id;
    }

    /** 台账分页：novelId / chapterId 均可空（null=不过滤），时间倒序。 */
    public List<LlmCallLogDO> findPage(Long novelId, Long chapterId, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE is_deleted=false");
        if (novelId != null) where.append(" AND novel_id=").append(novelId);
        if (chapterId != null) where.append(" AND chapter_id=").append(chapterId);
        return jdbc.query("""
                SELECT id, node, novel_id, chapter_id, model, prompt_tokens, completion_tokens,
                       total_tokens, latency_ms, status, error_msg, reasoning_text,
                       request_json, response_json, is_deleted
                FROM llm_call_log""" + where + " ORDER BY id DESC LIMIT " + limit + " OFFSET " + offset,
                MAPPER);
    }

    public long countBy(Long novelId, Long chapterId) {
        StringBuilder where = new StringBuilder(" WHERE is_deleted=false");
        if (novelId != null) where.append(" AND novel_id=").append(novelId);
        if (chapterId != null) where.append(" AND chapter_id=").append(chapterId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM llm_call_log" + where, Long.class);
        return count == null ? 0 : count;
    }

    public Totals totalsBy(Long novelId, Long chapterId) {
        StringBuilder where = new StringBuilder(" WHERE is_deleted=false");
        if (novelId != null) where.append(" AND novel_id=").append(novelId);
        if (chapterId != null) where.append(" AND chapter_id=").append(chapterId);
        return jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(prompt_tokens),0), "
                + "COALESCE(SUM(completion_tokens),0), COALESCE(SUM(total_tokens),0), "
                + "COALESCE(AVG(latency_ms),0) FROM llm_call_log" + where, (rs, i) -> new Totals(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
    }

    /** 近 N 天按 node×model×峰谷 分组用量（计费原始数据；is_peak 依赖价目表高峰时段，无价目行时为 false）。 */
    public List<UsageGroup> usageByNodeSince(int days) {
        return jdbc.query("""
                SELECT l.node, l.model,
                       (EXTRACT(HOUR FROM l.create_time AT TIME ZONE 'Asia/Shanghai') >= p.peak_start_hour
                        AND EXTRACT(HOUR FROM l.create_time AT TIME ZONE 'Asia/Shanghai') < p.peak_end_hour) AS is_peak,
                       COUNT(*) AS calls,
                       COALESCE(SUM(l.prompt_tokens),0) AS prompt_tokens,
                       COALESCE(SUM(COALESCE(l.cached_tokens,0)),0) AS cached_tokens,
                       COALESCE(SUM(l.completion_tokens),0) AS completion_tokens,
                       COALESCE(SUM(l.latency_ms),0) AS latency_sum
                FROM llm_call_log l
                LEFT JOIN llm_model_price p ON p.model = l.model AND p.is_deleted=false AND p.enabled=true
                WHERE l.is_deleted=false AND l.create_time > NOW() - (? || ' days')::interval
                GROUP BY 1, 2, 3
                ORDER BY 1
                """, (rs, i) -> new UsageGroup(rs.getString("node"), rs.getString("model"),
                rs.getBoolean("is_peak"), rs.getLong("calls"), rs.getLong("prompt_tokens"),
                rs.getLong("cached_tokens"), rs.getLong("completion_tokens"), rs.getLong("latency_sum")), days);
    }

    public LlmCallLogDO findById(long id) {
        List<LlmCallLogDO> rows = jdbc.query("""
                SELECT id, node, novel_id, chapter_id, model, prompt_tokens, completion_tokens,
                       total_tokens, latency_ms, status, error_msg, reasoning_text,
                       request_json, response_json, is_deleted
                FROM llm_call_log WHERE id=? AND is_deleted=false
                """, MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
