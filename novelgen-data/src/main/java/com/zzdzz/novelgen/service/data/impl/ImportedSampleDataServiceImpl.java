package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.ImportedSampleMapper;
import com.zzdzz.novelgen.model.entity.ImportedSampleDO;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 导入小说样本台账数据服务实现。 */
@Service
public class ImportedSampleDataServiceImpl extends ServiceImpl<ImportedSampleMapper, ImportedSampleDO>
        implements ImportedSampleDataService {

    @Override
    public List<ImportedSampleDO> listAlive() {
        return list(new QueryWrapper<ImportedSampleDO>()
                .orderByDesc("id"));
    }

    @Override
    public long insert(String title, String genre, int chunks, long totalChars, String source, String analysis) {
        return baseMapper.insert(title, genre, chunks, totalChars, source, analysis);
    }

    @Override
    public int linkPreset(String genre, long presetId) {
        return baseMapper.update(null, new UpdateWrapper<ImportedSampleDO>()
                .eq("genre", genre)
                .set("preset_id", presetId)
                .set("update_time", java.time.OffsetDateTime.now()));
    }

    @Override
    public int updateTags(long sampleId, String tagsJson) {
        return baseMapper.updateTags(sampleId, tagsJson);
    }
}
