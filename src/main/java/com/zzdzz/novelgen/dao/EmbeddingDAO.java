package com.zzdzz.novelgen.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** embeddings 向量表访问：全部 SQL 收口于此（pgvector，余弦距离）。 */
@Repository
public class EmbeddingDAO {

    /** 检索命中行。 */
    public record Hit(String sourceType, long sourceId, Integer chapterNo, String content, double distance) {}

    private final JdbcTemplate jdbc;

    public EmbeddingDAO(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入：同源覆盖（唯一索引 source_type+source_id，仅未删行）。 */
    public void upsert(String sourceType, long sourceId, long novelId, Integer chapterNo,
                       String content, float[] vec) {
        jdbc.update("""
                INSERT INTO embeddings (source_type, source_id, novel_id, chapter_no, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?::vector)
                ON CONFLICT (source_type, source_id) WHERE is_deleted = FALSE
                DO UPDATE SET embedding = EXCLUDED.embedding, content = EXCLUDED.content,
                              chapter_no = EXCLUDED.chapter_no, update_time = NOW()
                """, sourceType, sourceId, novelId, chapterNo, content, vectorLiteral(vec));
    }

    /** 余弦近邻检索，distance 升序。 */
    public List<Hit> search(long novelId, float[] queryVec, int limit) {
        String vec = vectorLiteral(queryVec);
        return jdbc.query("""
                SELECT source_type, source_id, chapter_no, content,
                       embedding <=> ?::vector AS distance
                FROM embeddings
                WHERE novel_id = ? AND is_deleted = FALSE
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, (rs, i) -> new Hit(rs.getString("source_type"), rs.getLong("source_id"),
                (Integer) rs.getObject("chapter_no"), rs.getString("content"), rs.getDouble("distance")),
                vec, novelId, vec, limit);
    }

    /** 未建索引的事实账（惰性补嵌用）。 */
    public List<Map<String, Object>> findMissingDigests(long novelId, int limit) {
        return jdbc.queryForList("""
                SELECT d.id AS source_id, c.chapter_no, d.content_md AS content
                FROM digests d
                JOIN chapters c ON c.id = d.chapter_id
                WHERE c.novel_id = ? AND d.is_deleted = FALSE
                  AND NOT EXISTS (SELECT 1 FROM embeddings e
                                  WHERE e.source_type = 'digest' AND e.source_id = d.id AND e.is_deleted = FALSE)
                ORDER BY c.chapter_no DESC
                LIMIT ?
                """, novelId, limit);
    }

    /** 未建索引的素材卡。 */
    public List<Map<String, Object>> findMissingCards(long novelId, int limit) {
        return jdbc.queryForList("""
                SELECT id AS source_id, kind, name FROM material_cards
                WHERE novel_id = ? AND is_deleted = FALSE
                  AND NOT EXISTS (SELECT 1 FROM embeddings e
                                  WHERE e.source_type = 'card' AND e.source_id = material_cards.id AND e.is_deleted = FALSE)
                ORDER BY id
                LIMIT ?
                """, novelId, limit);
    }

    public int countByNovel(long novelId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM embeddings WHERE novel_id = ? AND is_deleted = FALSE",
                Integer.class, novelId);
        return n == null ? 0 : n;
    }

    private static String vectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
