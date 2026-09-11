package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.ChapterDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 章数据访问：卷纲行、章纲回填、正文与状态机推进。 */
@Repository
public class ChapterDAO {

    private static final RowMapper<ChapterDO> MAPPER = (rs, i) -> new ChapterDO(
            rs.getLong("id"), rs.getLong("novel_id"), rs.getInt("chapter_no"),
            (Integer) rs.getObject("volume_no"), rs.getString("arc"), rs.getString("title"),
            rs.getString("pov"), rs.getString("outline_yaml"), rs.getString("full_text"),
            rs.getString("goal"), rs.getString("hook"), rs.getString("time_note"),
            rs.getString("rule_refs"), rs.getString("foreshadow_refs"),
            rs.getInt("budget_min"), rs.getInt("budget_max"), rs.getString("status"),
            rs.getInt("round"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public ChapterDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChapterDO> find(long novelId, int chapterNo) {
        List<ChapterDO> rows = jdbc.query("""
                SELECT * FROM chapters WHERE novel_id=? AND chapter_no=? AND is_deleted=false
                """, MAPPER, novelId, chapterNo);
        return rows.stream().findFirst();
    }

    public Optional<ChapterDO> findById(long chapterId) {
        List<ChapterDO> rows = jdbc.query(
                "SELECT * FROM chapters WHERE id=? AND is_deleted=false", MAPPER, chapterId);
        return rows.stream().findFirst();
    }

    /** 列表页摘要：full_text 不取（DO 中置 null）。 */
    public List<ChapterDO> listSummariesByNovel(long novelId) {
        return jdbc.query("""
                SELECT id, novel_id, chapter_no, volume_no, arc, title, pov, outline_yaml,
                       NULL AS full_text, goal, hook, time_note, rule_refs, foreshadow_refs,
                       budget_min, budget_max, status, round, is_deleted
                FROM chapters WHERE novel_id=? AND is_deleted=false ORDER BY chapter_no
                """, MAPPER, novelId);
    }

    public boolean exists(long novelId, int chapterNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chapters WHERE novel_id=? AND chapter_no=? AND is_deleted=false",
                Long.class, novelId, chapterNo);
        return count != null && count > 0;
    }

    public void insertPlan(long novelId, int chapterNo, Integer volumeNo, String arc, String title,
                           String goal, String hook, String timeNote, String ruleRefs, String foreshadowRefs,
                           int budgetMin, int budgetMax) {
        jdbc.update("""
                INSERT INTO chapters (novel_id, chapter_no, volume_no, arc, title, goal, hook, time_note,
                                      rule_refs, foreshadow_refs, budget_min, budget_max, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'NEW')
                """, novelId, chapterNo, volumeNo, arc, title, goal, hook, timeNote,
                ruleRefs, foreshadowRefs, budgetMin, budgetMax);
    }

    /** 章纲回填并推进状态；同时清掉旧的场景与门禁报告（外键顺序：先报告后场景）。 */
    public void resetForReoutline(long chapterId, String outlineYaml) {
        jdbc.update("DELETE FROM gate_reports WHERE chapter_id=?", chapterId);
        jdbc.update("DELETE FROM chapter_scenes WHERE chapter_id=?", chapterId);
        jdbc.update("UPDATE chapters SET outline_yaml=?, status='OUTLINED', update_time=NOW() WHERE id=?",
                outlineYaml, chapterId);
    }

    /** 卷纲规划编辑：改标题/目标/钩子/卷归属/字数预算（不改状态与正文）。 */
    public void updatePlan(long chapterId, Integer volumeNo, String arc, String title,
                           String goal, String hook, String timeNote, int budgetMin, int budgetMax) {
        jdbc.update("""
                UPDATE chapters SET volume_no=?, arc=?, title=?, goal=?, hook=?, time_note=?,
                       budget_min=?, budget_max=?, update_time=NOW()
                WHERE id=? AND is_deleted=false
                """, volumeNo, arc, title, goal, hook, timeNote, budgetMin, budgetMax, chapterId);
    }

    /** 仅未动笔的规划行可删（软删）。 */
    public void softDeletePlan(long chapterId) {
        jdbc.update("UPDATE chapters SET is_deleted=true, delete_time=NOW() WHERE id=? AND is_deleted=false",
                chapterId);
    }

    public void updateStatus(long chapterId, String status) {
        jdbc.update("UPDATE chapters SET status=?, update_time=NOW() WHERE id=?", status, chapterId);
    }

    public void updateStatusByNo(long novelId, int chapterNo, String status) {
        jdbc.update("""
                UPDATE chapters SET status=?, update_time=NOW()
                WHERE novel_id=? AND chapter_no=? AND is_deleted=false
                """, status, novelId, chapterNo);
    }

    public void saveFullText(long chapterId, String fullText) {
        jdbc.update("UPDATE chapters SET full_text=?, update_time=NOW() WHERE id=?", fullText, chapterId);
    }

    /** 无该章（如第 1 章无“上一章”）时返回 null，由调用方决定降级文案。 */
    public String findFullText(long novelId, int chapterNo) {
        List<String> rows = jdbc.query("""
                SELECT full_text FROM chapters
                WHERE novel_id=? AND chapter_no=? AND is_deleted=false AND full_text IS NOT NULL
                """, (rs, i) -> rs.getString(1), novelId, chapterNo);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
