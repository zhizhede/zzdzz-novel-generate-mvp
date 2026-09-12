package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 伏笔账本数据访问：埋设/回收指令的确定性查询口。 */
@Repository
public class ForeshadowDAO {

    private static final RowMapper<ForeshadowDO> MAPPER = (rs, i) -> new ForeshadowDO(
            rs.getLong("id"), rs.getLong("novel_id"), rs.getString("code"), rs.getString("content"),
            (Integer) rs.getObject("planted_in"), (Integer) rs.getObject("recovered_in"),
            (Integer) rs.getObject("proposed_in"), rs.getString("status"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public ForeshadowDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 素材库：全部伏笔及状态。 */
    public List<ForeshadowDO> listByNovel(long novelId) {
        return jdbc.query("""
                SELECT id, novel_id, code, content, planted_in, recovered_in, proposed_in, status, is_deleted
                FROM foreshadows WHERE novel_id=? AND is_deleted=false ORDER BY code
                """, MAPPER, novelId);
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

    /** 素材库人工修正：内容/埋设回收章/状态。 */
    public void update(long id, String content, Integer plantedIn, Integer recoveredIn, String status) {
        jdbc.update("""
                UPDATE foreshadows SET content=?, planted_in=?, recovered_in=?, status=?, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, content, plantedIn, recoveredIn, status, id);
    }

    public ForeshadowDO findByCode(long novelId, String code) {
        List<ForeshadowDO> rows = jdbc.query("""
                SELECT id, novel_id, code, content, planted_in, recovered_in, proposed_in, status, is_deleted
                FROM foreshadows WHERE novel_id=? AND code=? AND is_deleted=false
                """, MAPPER, novelId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ForeshadowDO findById(long id) {
        List<ForeshadowDO> rows = jdbc.query("""
                SELECT id, novel_id, code, content, planted_in, recovered_in, proposed_in, status, is_deleted
                FROM foreshadows WHERE id=? AND is_deleted=false
                """, MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** digest 自动提议：status='proposed'，planted/recovered 留空待人工采纳后编排。 */
    public void insertProposal(long novelId, String code, String content, int proposedIn) {
        jdbc.update("""
                INSERT INTO foreshadows (novel_id, code, content, proposed_in, status)
                VALUES (?, ?, ?, ?, 'proposed')
                """, novelId, code, content, proposedIn);
    }

    public boolean contentExists(long novelId, String content) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM foreshadows WHERE novel_id=? AND content=? AND is_deleted=false",
                Long.class, novelId, content);
        return count != null && count > 0;
    }

    /** 下一个可用编号：沿用 F1、F2… 序列。 */
    public String nextCode(long novelId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(SUBSTRING(code FROM 2)::INT), 0) FROM foreshadows
                WHERE novel_id=? AND is_deleted=false AND code ~ '^F[0-9]+$'
                """, Integer.class, novelId);
        return "F" + ((max == null ? 0 : max) + 1);
    }

    /** 规划 Agent 新伏笔建账：status='planned'，planted_in=排期埋设章。 */
    public void insertPlanned(long novelId, String code, String content, int plantedIn) {
        jdbc.update("""
                INSERT INTO foreshadows (novel_id, code, content, planted_in, status)
                VALUES (?, ?, ?, ?, 'planned')
                """, novelId, code, content, plantedIn);
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

    /** 规划采纳：proposed → planned，埋设章为规划中首次引用它的章（仅 proposed 可升，幂等）。 */
    public void promoteProposal(long id, int plantedIn) {
        jdbc.update("""
                UPDATE foreshadows SET status='planned', planted_in=?, update_time=NOW()
                WHERE id=? AND status='proposed' AND is_deleted=false
                """, plantedIn, id);
    }

    /** 规划排期回收：planted 且未定回收章的补上 recovered_in（digest 按章号自动推进状态）。 */
    public void scheduleRecovery(long id, int recoveredIn) {
        jdbc.update("""
                UPDATE foreshadows SET recovered_in=?, update_time=NOW()
                WHERE id=? AND status='planted' AND recovered_in IS NULL AND is_deleted=false
                """, recoveredIn, id);
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
