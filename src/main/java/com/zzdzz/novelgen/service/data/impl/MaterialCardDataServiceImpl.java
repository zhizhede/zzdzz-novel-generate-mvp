package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.MaterialCardMapper;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** material_cards 数据服务实现。 */
@Service
public class MaterialCardDataServiceImpl extends ServiceImpl<MaterialCardMapper, MaterialCardDTO> implements MaterialCardDataService {

    @Override
    public List<MaterialCardDTO> listByNovel(long novelId, String kind) {
        return baseMapper.listByNovel(novelId, kind);
    }

    @Override
    public MaterialCardDTO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public boolean exists(long novelId, String kind, String name) {
        return baseMapper.exists(novelId, kind, name);
    }

    @Override
    public int softDelete(long id) {
        return baseMapper.softDelete(id);
    }

    @Override
    public boolean hasCards(long novelId) {
        return baseMapper.hasCards(novelId);
    }

    @Override
    public void insert(long novelId, String kind, String name, List<String> aliases, String summary,
                       String contentMd, boolean pinned, String status, Integer sourceChapter) {
        baseMapper.insert(novelId, kind, name, toJson(aliases), summary, contentMd, pinned, status, sourceChapter);
    }

    @Override
    public void update(long id, String name, List<String> aliases, String summary, String contentMd,
                       Boolean pinned, String status, Integer sourceChapter) {
        baseMapper.update(id, name, toJson(aliases), summary, contentMd, pinned, status, sourceChapter);
    }

    private String toJson(List<String> aliases) {
        if (aliases == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(aliases);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
