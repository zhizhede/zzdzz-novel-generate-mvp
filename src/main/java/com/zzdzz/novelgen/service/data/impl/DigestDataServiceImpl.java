package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.DigestMapper;
import com.zzdzz.novelgen.model.entity.DigestDO;
import com.zzdzz.novelgen.service.data.DigestDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** digests 数据服务实现。 */
@Service
public class DigestDataServiceImpl extends ServiceImpl<DigestMapper, DigestDO> implements DigestDataService {

    @Override
    public List<DigestItem> listByNovel(long novelId) {
        List<DigestItem> out = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> m : baseMapper.listByNovel(novelId)) {
            out.add(new DigestItem(((Number) m.get("id")).longValue(),
                    ((Number) m.get("chapter_no")).intValue(),
                    (String) m.get("content_md"), (String) m.get("facts"),
                    String.valueOf(m.get("update_time"))));
        }
        return out;
    }

    @Override
    public int updateContent(long id, String contentMd, String factsJson) {
        return baseMapper.updateContent(id, contentMd, factsJson);
    }

    @Override
    public List<String> findRecent(long novelId, int beforeChapter, int n) {
        return baseMapper.findRecent(novelId, beforeChapter, n);
    }

    @Override
    public int insert(long chapterId, String contentMd, String factsJson) {
        return baseMapper.insert(chapterId, contentMd, factsJson);
    }

    @Override
    public boolean existsByChapter(long chapterId) {
        return baseMapper.existsByChapter(chapterId);
    }
}
