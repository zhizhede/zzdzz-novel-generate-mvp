package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zzdzz.novelgen.derive.mapper.VolumePlanMapper;
import com.zzdzz.novelgen.model.entity.derive.VolumePlanDO;
import com.zzdzz.novelgen.model.vo.derive.VolumePlanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 卷级规划只读查询。 */
@Service
@RequiredArgsConstructor
public class DeriveVolumeService {

    private final VolumePlanMapper volumePlanMapper;

    public List<VolumePlanVO> list(long runId) {
        return volumePlanMapper.selectList(new LambdaQueryWrapper<VolumePlanDO>()
                        .eq(VolumePlanDO::getRunId, runId)
                        .orderByAsc(VolumePlanDO::getVolumeNo))
                .stream()
                .map(v -> new VolumePlanVO(v.getVolumeNo(), v.getFromChapter(), v.getToChapter(),
                        v.getArc(), v.getLocalEvent(), v.getLocalClosure(), v.getNextHook(),
                        v.getGlobalRole(), v.getStatus()))
                .toList();
    }
}
