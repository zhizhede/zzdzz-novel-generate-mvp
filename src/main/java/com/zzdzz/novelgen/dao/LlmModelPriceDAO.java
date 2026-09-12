package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.LlmModelPriceDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 模型价目数据访问。 */
@Repository
public class LlmModelPriceDAO {

    private static final RowMapper<LlmModelPriceDO> MAPPER = (rs, i) -> new LlmModelPriceDO(
            rs.getLong("id"), rs.getString("model"), rs.getString("currency"),
            rs.getBigDecimal("idle_input_hit"), rs.getBigDecimal("idle_input_miss"), rs.getBigDecimal("idle_output"),
            rs.getBigDecimal("peak_input_hit"), rs.getBigDecimal("peak_input_miss"), rs.getBigDecimal("peak_output"),
            rs.getInt("peak_start_hour"), rs.getInt("peak_end_hour"), rs.getBoolean("enabled"),
            rs.getString("remark"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public LlmModelPriceDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LlmModelPriceDO> listAll() {
        return jdbc.query("SELECT * FROM llm_model_price WHERE is_deleted=false ORDER BY id", MAPPER);
    }

    public LlmModelPriceDO findById(long id) {
        List<LlmModelPriceDO> rows = jdbc.query(
                "SELECT * FROM llm_model_price WHERE id=? AND is_deleted=false", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void update(long id, java.math.BigDecimal idleInputHit, java.math.BigDecimal idleInputMiss,
                       java.math.BigDecimal idleOutput, java.math.BigDecimal peakInputHit,
                       java.math.BigDecimal peakInputMiss, java.math.BigDecimal peakOutput,
                       int peakStartHour, int peakEndHour, String remark) {
        jdbc.update("""
                UPDATE llm_model_price SET idle_input_hit=?, idle_input_miss=?, idle_output=?,
                       peak_input_hit=?, peak_input_miss=?, peak_output=?,
                       peak_start_hour=?, peak_end_hour=?, remark=?, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, idleInputHit, idleInputMiss, idleOutput, peakInputHit, peakInputMiss, peakOutput,
                peakStartHour, peakEndHour, remark, id);
    }
}
