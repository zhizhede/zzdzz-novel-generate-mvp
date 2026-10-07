package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.CharacterStateMapper;
import com.zzdzz.novelgen.model.entity.CharacterStateDO;
import com.zzdzz.novelgen.service.data.CharacterStateDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** character_states 数据服务实现。 */
@Service
public class CharacterStateDataServiceImpl extends ServiceImpl<CharacterStateMapper, CharacterStateDO>
        implements CharacterStateDataService {

    @Override
    public void replaceChapter(long novelId, int chapterNo, List<Row> rows) {
        baseMapper.deleteByChapter(novelId, chapterNo);
        for (Row r : rows) {
            baseMapper.insertRow(novelId, chapterNo, r.name(), r.location(), r.possessions());
        }
    }

    @Override
    public List<CharacterStateDO> listByChapter(long novelId, int chapterNo) {
        return baseMapper.listByChapter(novelId, chapterNo);
    }

    @Override
    public List<CharacterStateDO> listByNovel(long novelId) {
        return baseMapper.listByNovel(novelId);
    }

    @Override
    public CharacterStateDO latestBefore(long novelId, String name, int beforeChapter) {
        return baseMapper.findLatestBeforeForName(novelId, name, beforeChapter);
    }
}
