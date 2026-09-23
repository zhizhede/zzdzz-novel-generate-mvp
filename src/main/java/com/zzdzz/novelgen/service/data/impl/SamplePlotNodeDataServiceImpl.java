package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.SamplePlotNodeMapper;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** 导入样本剧情结构树数据服务实现。 */
@Service
public class SamplePlotNodeDataServiceImpl extends ServiceImpl<SamplePlotNodeMapper, SamplePlotNodeDTO>
        implements SamplePlotNodeDataService {

    @Override
    public List<SamplePlotNodeDTO> listBySample(long sampleId) {
        return list(new QueryWrapper<SamplePlotNodeDTO>()
                .eq("sample_id", sampleId)
                .eq("is_deleted", false)
                .orderByAsc("level")
                .orderByAsc("seq"));
    }

    @Override
    public SamplePlotNodeDTO findBySeq(long sampleId, String level, int seq) {
        return getOne(new QueryWrapper<SamplePlotNodeDTO>()
                .eq("sample_id", sampleId)
                .eq("level", level)
                .eq("seq", seq)
                .eq("is_deleted", false)
                .last("LIMIT 1"));
    }

    @Override
    public long insertNode(long sampleId, String level, int seq, int parentSeq, String title,
                           String summary, String beats, String meta) {
        return baseMapper.insertNode(sampleId, level, seq, parentSeq, title, summary, beats, meta);
    }

    @Override
    public int softDeleteByLevel(long sampleId, String level) {
        return baseMapper.update(null, new UpdateWrapper<SamplePlotNodeDTO>()
                .eq("sample_id", sampleId)
                .eq("level", level)
                .eq("is_deleted", false)
                .set("is_deleted", true)
                .set("delete_time", OffsetDateTime.now()));
    }
}
