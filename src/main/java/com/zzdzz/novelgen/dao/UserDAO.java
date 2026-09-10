package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.UserDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 用户表数据访问（MVP 仅 admin 种子）。 */
@Repository
public class UserDAO {

    private static final RowMapper<UserDO> MAPPER = (rs, i) -> new UserDO(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getString("role"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public UserDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserDO findAliveByUsername(String username) {
        List<UserDO> rows = jdbc.query("""
                SELECT id, username, password_hash, role, is_deleted
                FROM users WHERE username=? AND is_deleted=false
                """, MAPPER, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long findIdByUsername(String username) {
        List<Long> rows = jdbc.query(
                "SELECT id FROM users WHERE username=? AND is_deleted=false",
                (rs, i) -> rs.getLong(1), username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(String username, String passwordHash, String role) {
        return jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, username, passwordHash, role);
    }
}
