package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.EmbeddingDO;

import java.util.List;
import java.util.Map;

/** embeddings 数据服务接口（原 EmbeddingDAO）。 */
public interface EmbeddingDataService extends IService<EmbeddingDO> {

    /** 检索命中行。 */
    record Hit(String sourceType, long sourceId, Integer chapterNo, String content, double distance) {
    }

    void upsert(String sourceType, long sourceId, long novelId, Integer chapterNo,
                String content, float[] vec);

    List<Hit> search(long novelId, float[] queryVec, int limit);

    /** 未建索引的事实账（惰性补嵌用）。 */
    List<Map<String, Object>> findMissingDigests(long novelId, int limit);

    /** 未建索引的素材卡。 */
    List<Map<String, Object>> findMissingCards(long novelId, int limit);

    int countByNovel(long novelId);
}
