package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.CanonDocMapper;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** canon_docs 数据服务实现。 */
@Service
public class CanonDocDataServiceImpl extends ServiceImpl<CanonDocMapper, CanonDocDO> implements CanonDocDataService {

    @Override
    public boolean exists(long novelId, String kind, String name) {
        return baseMapper.exists(novelId, kind, name);
    }

    @Override
    public int insert(long novelId, String kind, String name, String content) {
        return baseMapper.insert(novelId, kind, name, content);
    }

    @Override
    public String findFirstByKind(long novelId, String kind) {
        return baseMapper.findFirstByKind(novelId, kind);
    }

    @Override
    public List<CanonDocDO> listByNovel(long novelId) {
        return baseMapper.listByNovel(novelId);
    }

    @Override
    public Long findId(long novelId, String kind, String name) {
        return baseMapper.findId(novelId, kind, name);
    }

    @Override
    public String findContentByKindName(long novelId, String kind, String name) {
        return baseMapper.findContentByKindName(novelId, kind, name);
    }

    @Override
    public CanonDocDO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public int updateContent(long id, String content) {
        return baseMapper.updateContent(id, content);
    }

    @Override
    public int softDelete(long id) {
        return baseMapper.softDelete(id);
    }
}
