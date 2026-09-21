package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.EmbeddingMapper;
import com.zzdzz.novelgen.model.dto.EmbeddingDTO;
import com.zzdzz.novelgen.service.data.EmbeddingDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** embeddings 数据服务实现。 */
@Service
public class EmbeddingDataServiceImpl extends ServiceImpl<EmbeddingMapper, EmbeddingDTO>
        implements EmbeddingDataService {

    @Override
    public void upsert(String sourceType, long sourceId, long novelId, Integer chapterNo,
                       String content, float[] vec) {
        baseMapper.upsert(sourceType, sourceId, novelId, chapterNo, content, vectorLiteral(vec));
    }

    @Override
    public List<Hit> search(long novelId, float[] queryVec, int limit) {
        return baseMapper.search(novelId, vectorLiteral(queryVec), limit);
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

    @Override
    public List<MissingRow> findMissingDigests(long novelId, int limit) {
        return baseMapper.findMissingDigests(novelId, limit);
    }

    @Override
    public List<MissingRow> findMissingCards(long novelId, int limit) {
        return baseMapper.findMissingCards(novelId, limit);
    }

    @Override
    public int countByNovel(long novelId) {
        return baseMapper.countByNovel(novelId);
    }
}
