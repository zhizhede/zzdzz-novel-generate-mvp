package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.SampleCardMapper;
import com.zzdzz.novelgen.model.entity.SampleCardDO;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 导入样本结构化资产卡数据服务实现。 */
@Service
public class SampleCardDataServiceImpl extends ServiceImpl<SampleCardMapper, SampleCardDO>
        implements SampleCardDataService {

    @Override
    public List<SampleCardDO> listBySample(long sampleId) {
        return list(new QueryWrapper<SampleCardDO>()
                .eq("sample_id", sampleId)
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
    public int deleteBySample(long sampleId) {
        return baseMapper.delete(new QueryWrapper<SampleCardDO>().eq("sample_id", sampleId));
    }

    @Override
    public void deleteCard(long cardId) {
        baseMapper.deleteById(cardId);
    }
}
