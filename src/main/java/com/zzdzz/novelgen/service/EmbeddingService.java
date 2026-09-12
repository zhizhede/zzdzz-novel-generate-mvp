package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.dao.EmbeddingDAO;
import com.zzdzz.novelgen.llm.LlmException;
import com.zzdzz.novelgen.llm.MiniMaxEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 语义检索：事实账摘要 + 素材卡的向量索引与召回。
 * 惰性索引——ensureNovelIndexed 在打包场景上下文时自动补嵌缺失项（幂等 upsert，写入路径零改动）；
 * 检索注入 fail-open——向量索引是增强不是依赖，任何失败都只降级为「没有该段落」。
 * 阈值/条数/开关全在 Tuning（rag_enabled / rag_top_k / rag_max_distance）。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 单次批量向量化上限（embo-01 批量调用条数）。 */
    private static final int BATCH = 16;

    public record Hit(String sourceType, Integer chapterNo, String label, String content, double distance) {}

    private final MiniMaxEmbeddingClient client;
    private final EmbeddingDAO dao;
    private final TuningService tuning;
    private final MaterialCardService cardService;

    public EmbeddingService(MiniMaxEmbeddingClient client, EmbeddingDAO dao,
                            TuningService tuning, MaterialCardService cardService) {
        this.client = client;
        this.dao = dao;
        this.tuning = tuning;
        this.cardService = cardService;
    }

    public boolean enabled() {
        return tuning.d("rag_enabled", 1) > 0;
    }

    /**
     * 语义召回并格式化为注入段落：带出处（事实账标章号、素材卡标类型+名）。
     * recentFloor 之下的近章事实账不召回（前情摘要已覆盖），避免重复注入。
     * 开关关闭/无命中/调用失败一律返回 null。
     */
    public String searchSection(long novelId, int chapterNo, String query, int recentFloor) {
        if (!enabled()) return null;
        try {
            ensureNovelIndexed(novelId);
            int topK = tuning.i("rag_top_k", 6);
            double maxDist = tuning.d("rag_max_distance", 0.55);
            List<EmbeddingDAO.Hit> hits = dao.search(novelId,
                    client.embed(novelId, MiniMaxEmbeddingClient.TYPE_QUERY, List.of(query)).get(0),
                    topK + 8);
            List<Hit> picked = new ArrayList<>();
            for (EmbeddingDAO.Hit h : hits) {
                if (picked.size() >= topK) break;
                if (h.distance() > maxDist) break;
                if ("digest".equals(h.sourceType())) {
                    if (h.chapterNo() != null && h.chapterNo() >= recentFloor) continue; // 近章已在摘要里
                    picked.add(new Hit("digest", h.chapterNo(), "第" + h.chapterNo() + "章·事实账",
                            h.content(), h.distance()));
                } else {
                    picked.add(new Hit("card", null, "素材卡", h.content(), h.distance()));
                }
            }
            if (picked.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Hit h : picked) {
                sb.append("- 【").append(h.label()).append("】")
                        .append(h.content().strip().replaceAll("\\s+", " ")).append('\n');
            }
            log.info("RAG 召回 {} 条（novel={} chapter={} query {} 字）", picked.size(), novelId, chapterNo,
                    query.length());
            return sb.toString().strip();
        } catch (LlmException e) {
            log.warn("RAG 召回失败（向量化），本场景降级不注入：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("RAG 召回异常，本场景降级不注入：{}", e.getMessage());
            return null;
        }
    }

    /** 惰性补嵌：事实账 + 素材卡中尚未建向量的条目，分批向量化。返回本次补嵌条数（fail-open）。 */
    public int ensureNovelIndexed(long novelId) {
        int indexed = 0;
        try {
            List<Map<String, Object>> digests = dao.findMissingDigests(novelId, 64);
            for (int i = 0; i < digests.size(); i += BATCH) {
                indexed += indexBatch(novelId, digests.subList(i, Math.min(i + BATCH, digests.size())));
            }
            List<Map<String, Object>> cards = dao.findMissingCards(novelId, 64);
            for (int i = 0; i < cards.size(); i += BATCH) {
                indexed += indexCardsBatch(novelId, cards.subList(i, Math.min(i + BATCH, cards.size())));
            }
        } catch (Exception e) {
            log.warn("惰性向量索引失败（下次场景打包重试）：{}", e.getMessage());
        }
        return indexed;
    }

    /** 手动回填入口（素材库按钮）：补嵌并返回总条数。 */
    public int backfillNovel(long novelId) {
        if (!enabled()) {
            throw new IllegalStateException("RAG 开关未开启（tuning: rag_enabled）");
        }
        int before = dao.countByNovel(novelId);
        ensureNovelIndexed(novelId);
        int added = dao.countByNovel(novelId) - before;
        log.info("手动回填向量索引：novel={} 新增 {} 条，共 {} 条", novelId, added, dao.countByNovel(novelId));
        return added;
    }

    public int countByNovel(long novelId) {
        return dao.countByNovel(novelId);
    }

    private int indexBatch(long novelId, List<Map<String, Object>> rows) {
        List<String> texts = rows.stream().map(r -> String.valueOf(r.get("content"))).toList();
        List<float[]> vecs = client.embed(novelId, MiniMaxEmbeddingClient.TYPE_DB, texts);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            dao.upsert("digest", ((Number) r.get("source_id")).longValue(), novelId,
                    (Integer) r.get("chapter_no"), texts.get(i), vecs.get(i));
        }
        return rows.size();
    }

    private int indexCardsBatch(long novelId, List<Map<String, Object>> rows) {
        List<String> texts = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            var card = cardService.get(((Number) r.get("source_id")).longValue());
            texts.add(cardService.embeddingText(card));
        }
        List<float[]> vecs = client.embed(novelId, MiniMaxEmbeddingClient.TYPE_DB, texts);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            dao.upsert("card", ((Number) r.get("source_id")).longValue(), novelId,
                    null, texts.get(i), vecs.get(i));
        }
        return rows.size();
    }
}
