package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.SampleParseTaskMapper;
import com.zzdzz.novelgen.model.dto.SampleParseTaskDTO;
import com.zzdzz.novelgen.service.data.SampleParseTaskDataService;
import org.springframework.stereotype.Service;

/** 导入小说深度解析任务数据服务实现。 */
@Service
public class SampleParseTaskDataServiceImpl extends ServiceImpl<SampleParseTaskMapper, SampleParseTaskDTO>
        implements SampleParseTaskDataService {

    @Override
    public SampleParseTaskDTO findAliveBySample(long sampleId) {
        return getOne(new QueryWrapper<SampleParseTaskDTO>()
                .eq("sample_id", sampleId)
                .eq("is_deleted", false)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    public long resetForRun(long sampleId, String mode) {
        SampleParseTaskDTO row = findAliveBySample(sampleId);
        if (row == null) {
            row = new SampleParseTaskDTO();
            row.setSampleId(sampleId);
            row.setMode(mode);
            row.setStatus("QUEUED");
            row.setTotalUnits(0);
            row.setDoneUnits(0);
            row.setStage("");
            row.setMessage(null);
            save(row);
            return row.getId();
        }
        update(new UpdateWrapper<SampleParseTaskDTO>()
                .eq("id", row.getId())
                .set("mode", mode)
                .set("status", "QUEUED")
                .set("total_units", 0)
                .set("done_units", 0)
                .set("stage", "")
                .set("message", null));
        return row.getId();
    }

    @Override
    public int casStatus(long taskId, String fromStatus, String toStatus) {
        return baseMapper.update(null, new UpdateWrapper<SampleParseTaskDTO>()
                .eq("id", taskId)
                .eq("status", fromStatus)
                .eq("is_deleted", false)
                .set("status", toStatus));
    }

    @Override
    public void updateProgress(long taskId, int doneUnits, String stage) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDTO>()
                .eq("id", taskId)
                .eq("is_deleted", false)
                .set("done_units", doneUnits)
                .set("stage", stage));
    }

    @Override
    public void updateTotal(long taskId, int totalUnits) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDTO>()
                .eq("id", taskId)
                .eq("is_deleted", false)
                .set("total_units", totalUnits));
    }

    @Override
    public void finish(long taskId, String status, String stage, String message) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDTO>()
                .eq("id", taskId)
                .eq("is_deleted", false)
                .set("status", status)
                .set("stage", stage)
                .set("message", message == null || message.isBlank() ? null
                        : message.substring(0, Math.min(message.length(), 256))));
    }
}
