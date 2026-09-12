package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.TuningDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** tuning 表访问：全部 SQL 收口于此。 */
@Repository
public class TuningDAO {

    private final JdbcTemplate jdbc;

    public TuningDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TuningDO> findAll() {
        return jdbc.query("""
                SELECT id, tkey, tvalue, description FROM tuning
                WHERE is_deleted = FALSE ORDER BY tkey
                """, (rs, i) -> new TuningDO(rs.getLong("id"), rs.getString("tkey"),
                rs.getString("tvalue"), rs.getString("description")));
    }

    public void updateValue(String key, String value) {
        jdbc.update("UPDATE tuning SET tvalue=?, update_time=NOW() WHERE tkey=? AND is_deleted=FALSE",
                value, key);
    }
}
