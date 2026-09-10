package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 伏笔账本数据访问：埋设/回收指令的确定性查询口。 */
@Repository
public class ForeshadowDAO {

    private final JdbcTemplate jdbc;

    public ForeshadowDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long novelId, String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM foreshadows WHERE novel_id=? AND code=? AND is_deleted=false",
                Long.class, novelId, code);
        return count != null && count > 0;
    }

    public void insert(long novelId, String code, String content, int plantedIn, int recoveredIn) {
        jdbc.update("INSERT INTO foreshadows (novel_id, code, content, planted_in, recovered_in) VALUES (?, ?, ?, ?, ?)",
                novelId, code, content, plantedIn, recoveredIn);
    }

    /** 本章需埋设或需回收的伏笔指令 */
    public List<String> findDirectives(long novelId, int chapterNo) {
        return jdbc.queryForList("""
                SELECT CASE WHEN planted_in = ? THEN '埋设：' ELSE '回收：' END || code || '——' || content
                FROM foreshadows
                WHERE novel_id=? AND is_deleted=false
                  AND ((planted_in = ? AND status = 'planned') OR (recovered_in = ? AND status = 'planted'))
                """, String.class, chapterNo, novelId, chapterNo, chapterNo);
    }

    public void markPlanted(long novelId, int chapterNo) {
        jdbc.update("""
                UPDATE foreshadows SET status='planted', update_time=NOW()
                WHERE novel_id=? AND planted_in <= ? AND status='planned' AND is_deleted=false
                """, novelId, chapterNo);
    }

    public void markRecovered(long novelId, int chapterNo) {
        jdbc.update("""
                UPDATE foreshadows SET status='recovered', update_time=NOW()
                WHERE novel_id=? AND recovered_in <= ? AND status='planted' AND is_deleted=false
                """, novelId, chapterNo);
    }
}
