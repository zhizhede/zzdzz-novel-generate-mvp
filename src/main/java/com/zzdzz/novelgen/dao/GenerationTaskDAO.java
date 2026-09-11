package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 生成队列数据访问：任务行 = 一次 [from,to] 连跑请求，状态机 QUEUED→RUNNING→DONE/STOPPED/CANCELED。 */
@Repository
public class GenerationTaskDAO {

    private final JdbcTemplate jdbc;

    public GenerationTaskDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long novelId, int fromChapter, int toChapter, Long submittedBy) {
        return jdbc.queryForObject("""
                INSERT INTO generation_tasks (novel_id, from_chapter, to_chapter, submitted_by)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, novelId, fromChapter, toChapter, submittedBy);
    }

    /** 列表行：联作品表带标题，active（排队/运行中）置顶。 */
    public record TaskRow(long id, long novelId, String novelTitle, int fromChapter, int toChapter,
                          String status, int doneChapters, Integer currentChapter, String lastMessage,
                          String createTime) {
    }

    public List<TaskRow> list(int limit) {
        return jdbc.query("""
                SELECT t.id, t.novel_id, n.title, t.from_chapter, t.to_chapter, t.status,
                       t.done_chapters, t.current_chapter, t.last_message, t.create_time
                FROM generation_tasks t JOIN novels n ON n.id = t.novel_id
                WHERE t.is_deleted = FALSE
                ORDER BY CASE WHEN t.status IN ('QUEUED', 'RUNNING') THEN 0 ELSE 1 END, t.id DESC
                LIMIT ?
                """, (rs, i) -> new TaskRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getInt(7), (Integer) rs.getObject(8),
                rs.getString(9), rs.getObject(10, java.time.OffsetDateTime.class)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))), limit);
    }

    /** 取队首排队任务并占位为 RUNNING（单 worker 无竞争，条件更新兜底）。 */
    public TaskRow claimNextQueued() {
        List<Long> ids = jdbc.query("""
                SELECT id FROM generation_tasks WHERE status = 'QUEUED' ORDER BY id LIMIT 1
                """, (rs, i) -> rs.getLong(1));
        if (ids.isEmpty()) {
            return null;
        }
        int claimed = jdbc.update(
                "UPDATE generation_tasks SET status='RUNNING', current_chapter=from_chapter, update_time=now() WHERE id=? AND status='QUEUED'",
                ids.get(0));
        if (claimed == 0) {
            return null;
        }
        List<TaskRow> rows = jdbc.query("""
                SELECT t.id, t.novel_id, n.title, t.from_chapter, t.to_chapter, t.status,
                       t.done_chapters, t.current_chapter, t.last_message, t.create_time
                FROM generation_tasks t JOIN novels n ON n.id = t.novel_id WHERE t.id = ?
                """, (rs, i) -> new TaskRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getInt(7), (Integer) rs.getObject(8),
                rs.getString(9), rs.getObject(10, java.time.OffsetDateTime.class)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))), ids.get(0));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateProgress(long id, int doneChapters, Integer currentChapter, String message) {
        jdbc.update("""
                UPDATE generation_tasks SET done_chapters=?, current_chapter=?, last_message=?, update_time=now()
                WHERE id=?
                """, doneChapters, currentChapter, message, id);
    }

    public void updateStatus(long id, String status, String message) {
        jdbc.update("""
                UPDATE generation_tasks SET status=?, last_message=?, update_time=now() WHERE id=?
                """, status, message, id);
    }

    /** 取消排队中任务；返回 0 表示已不是 QUEUED（可能已被占位执行）。 */
    public int cancelQueued(long id) {
        return jdbc.update(
                "UPDATE generation_tasks SET status='CANCELED', last_message='提交后取消', update_time=now() WHERE id=? AND status='QUEUED'",
                id);
    }

    public String findStatus(long id) {
        List<String> rows = jdbc.query("SELECT status FROM generation_tasks WHERE id=?",
                (rs, i) -> rs.getString(1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 应用重启恢复：上次运行中的任务不可能还活着，重新排队。 */
    public int resetInterrupted() {
        return jdbc.update("""
                UPDATE generation_tasks SET status='QUEUED', last_message='应用重启，重新排队', update_time=now()
                WHERE status='RUNNING'
                """);
    }

    /** 运行中任务（/status 派生用），无则 null。 */
    public TaskRow findRunning() {
        List<TaskRow> rows = jdbc.query("""
                SELECT t.id, t.novel_id, n.title, t.from_chapter, t.to_chapter, t.status,
                       t.done_chapters, t.current_chapter, t.last_message, t.create_time
                FROM generation_tasks t JOIN novels n ON n.id = t.novel_id
                WHERE t.status = 'RUNNING' ORDER BY t.id LIMIT 1
                """, (rs, i) -> new TaskRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getInt(7), (Integer) rs.getObject(8),
                rs.getString(9), rs.getObject(10, java.time.OffsetDateTime.class)
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
