package com.zzdzz.novelgen.dao;

import com.zzdzz.novelgen.model.entity.SceneDO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 场景数据访问：章纲物化、草稿写回、门禁状态。 */
@Repository
public class SceneDAO {

    private static final RowMapper<SceneDO> MAPPER = (rs, i) -> new SceneDO(
            rs.getLong("id"), rs.getLong("chapter_id"), rs.getInt("scene_no"),
            rs.getString("goal"), rs.getString("present"), rs.getString("must_reveal"),
            rs.getString("must_not"), rs.getInt("words_budget"), rs.getString("draft_text"),
            rs.getString("gate_status"), rs.getInt("revision_round"), rs.getBoolean("is_deleted"));

    private final JdbcTemplate jdbc;

    public SceneDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countByChapter(long chapterId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chapter_scenes WHERE chapter_id=? AND is_deleted=false",
                Long.class, chapterId);
        return count == null ? 0 : count.intValue();
    }

    public List<SceneDO> findByChapter(long chapterId) {
        return jdbc.query("""
                SELECT * FROM chapter_scenes WHERE chapter_id=? AND is_deleted=false ORDER BY scene_no
                """, MAPPER, chapterId);
    }

    public Long findId(long chapterId, int sceneNo) {
        return jdbc.queryForObject(
                "SELECT id FROM chapter_scenes WHERE chapter_id=? AND scene_no=? AND is_deleted=false",
                Long.class, chapterId, sceneNo);
    }

    /** 覆盖式物化：调用方已先行删除该章门禁报告。 */
    public void replaceAll(long chapterId, List<String> goals, List<String> presentJson,
                           List<String> mustRevealJson, List<String> mustNotJson, List<Integer> words) {
        jdbc.update("DELETE FROM chapter_scenes WHERE chapter_id=?", chapterId);
        for (int i = 0; i < goals.size(); i++) {
            jdbc.update("""
                    INSERT INTO chapter_scenes (chapter_id, scene_no, goal, present, must_reveal, must_not, words_budget)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                    """, chapterId, i + 1, goals.get(i), presentJson.get(i),
                    mustRevealJson.get(i), mustNotJson.get(i), words.get(i));
        }
    }

    public void saveDraft(long chapterId, int sceneNo, String draftText) {
        jdbc.update("""
                UPDATE chapter_scenes SET draft_text=?, update_time=NOW()
                WHERE chapter_id=? AND scene_no=?
                """, draftText, chapterId, sceneNo);
    }

    public void applyRevise(long sceneId, String draftText) {
        jdbc.update("""
                UPDATE chapter_scenes SET draft_text=?, revision_round=revision_round+1, update_time=NOW()
                WHERE id=?
                """, draftText, sceneId);
    }

    public void updateGateStatus(long sceneId, String status) {
        jdbc.update("UPDATE chapter_scenes SET gate_status=? WHERE id=?", status, sceneId);
    }

    public List<String> findPassedDrafts(long chapterId) {
        return jdbc.queryForList("""
                SELECT draft_text FROM chapter_scenes
                WHERE chapter_id=? AND gate_status='PASSED' AND is_deleted=false ORDER BY scene_no
                """, String.class, chapterId);
    }
}
