package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.EmbeddingDTO;

import java.util.List;
import java.util.Map;

/** embeddings 数据服务接口（原 EmbeddingDAO）。 */
public interface EmbeddingDataService extends IService<EmbeddingDTO> {

    /** 检索命中行。 */
    record Hit(String sourceType, long sourceId, Integer chapterNo, String content, double distance) {
    }

    /** 待补嵌条目：事实账带 chapterNo+content，素材卡带 kind+name（缺席列为 null）。 */
    record MissingRow(long sourceId, Integer chapterNo, String content, String kind, String name) {
    }

    void upsert(String sourceType, long sourceId, long novelId, Integer chapterNo,
                String content, float[] vec);

    List<Hit> search(long novelId, float[] queryVec, int limit);

    /** 未建索引的事实账（惰性补嵌用）。 */
    List<MissingRow> findMissingDigests(long novelId, int limit);

    /** 未建索引的素材卡。 */
    List<MissingRow> findMissingCards(long novelId, int limit);

    int countByNovel(long novelId);

    /**
     * 硬删本书全部向量（含软删残留）——只给「覆盖重算」用：向量是纯派生索引，清掉即由源行重建。
     * 不用软删是因为唯一索引 uq_embeddings_source 只管活行，软删会留下死行、让每次重算多积一批。
     */
    int deleteByNovel(long novelId);
}
