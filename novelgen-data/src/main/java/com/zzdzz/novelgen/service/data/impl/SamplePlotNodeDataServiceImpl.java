package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.SamplePlotNodeMapper;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 导入样本剧情结构树数据服务实现。 */
@Service
public class SamplePlotNodeDataServiceImpl extends ServiceImpl<SamplePlotNodeMapper, SamplePlotNodeDO>
        implements SamplePlotNodeDataService {

    @Override
    public List<SamplePlotNodeDO> listBySample(long sampleId) {
        return list(new QueryWrapper<SamplePlotNodeDO>()
                .eq("sample_id", sampleId)
                .orderByAsc("level")
                .orderByAsc("seq"));
    }

    @Override
    public SamplePlotNodeDO findBySeq(long sampleId, String level, int seq) {
        return getOne(new QueryWrapper<SamplePlotNodeDO>()
                .eq("sample_id", sampleId)
                .eq("level", level)
                .eq("seq", seq)
                .last("LIMIT 1"));
    }

    @Override
    public long insertNode(long sampleId, String level, int seq, int parentSeq, String title,
                           String summary, String beats, String meta) {
        return baseMapper.insertNode(sampleId, level, seq, parentSeq, title, summary, beats, meta);
    }

    @Override
    public int deleteByLevel(long sampleId, String level) {
        return baseMapper.delete(new QueryWrapper<SamplePlotNodeDO>()
                .eq("sample_id", sampleId)
                .eq("level", level));
    }
}
