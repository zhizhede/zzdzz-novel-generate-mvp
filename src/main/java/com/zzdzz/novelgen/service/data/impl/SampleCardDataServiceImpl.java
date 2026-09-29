package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.SampleCardMapper;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** 导入样本结构化资产卡数据服务实现。 */
@Service
public class SampleCardDataServiceImpl extends ServiceImpl<SampleCardMapper, SampleCardDTO>
        implements SampleCardDataService {

    @Override
    public List<SampleCardDTO> listBySample(long sampleId) {
        return list(new QueryWrapper<SampleCardDTO>()
                .eq("sample_id", sampleId)
                .eq("is_deleted", false)
                .orderByDesc("importance")
                .orderByAsc("id"));
    }

    @Override
    public long insertCard(long sampleId, String kind, String name, String aliases, String summary,
                           String contentMd, String relations, int importance, Integer firstSeq, int mentions) {
        return baseMapper.insertCard(sampleId, kind, name, aliases, summary, contentMd,
                relations, importance, firstSeq, mentions);
    }

    @Override
    public int softDeleteBySample(long sampleId) {
        return baseMapper.update(null, new UpdateWrapper<SampleCardDTO>()
                .eq("sample_id", sampleId)
                .eq("is_deleted", false)
                .set("is_deleted", true)
                .set("delete_time", OffsetDateTime.now()));
    }

    @Override
    public void softDeleteById(long cardId) {
        baseMapper.update(null, new UpdateWrapper<SampleCardDTO>()
                .eq("id", cardId)
                .eq("is_deleted", false)
                .set("is_deleted", true)
                .set("delete_time", OffsetDateTime.now()));
    }
}
