package com.zzdzz.novelgen.service.data.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.WorldStateMapper;
import com.zzdzz.novelgen.model.entity.WorldStateDO;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** world_states 数据服务实现。 */
@Service
@RequiredArgsConstructor
public class WorldStateDataServiceImpl extends ServiceImpl<WorldStateMapper, WorldStateDO>
        implements WorldStateDataService {

    private final ObjectMapper mapper;


    @Override
    public void upsert(long novelId, int chapterNo, Object state) {
        try {
            baseMapper.upsert(novelId, chapterNo, mapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("世界状态写入失败", e);
        }
    }

    @Override
    public String findLatestBefore(long novelId, int beforeChapter) {
        return baseMapper.findLatestBefore(novelId, beforeChapter);
    }

    @Override
    public List<StateRow> listByNovel(long novelId, int limit) {
        return baseMapper.listByNovel(novelId, limit);
    }

    @Override
    public void updateByChapter(long novelId, int chapterNo, String stateJson) {
        int n = baseMapper.updateByChapter(novelId, chapterNo, stateJson);
        if (n == 0) {
            baseMapper.insertRaw(novelId, chapterNo, stateJson);
        }
    }
}
