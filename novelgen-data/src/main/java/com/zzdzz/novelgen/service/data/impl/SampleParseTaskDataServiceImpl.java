package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.SampleParseTaskMapper;
import com.zzdzz.novelgen.model.entity.SampleParseTaskDO;
import com.zzdzz.novelgen.service.data.SampleParseTaskDataService;
import org.springframework.stereotype.Service;

/** 导入小说深度解析任务数据服务实现。 */
@Service
public class SampleParseTaskDataServiceImpl extends ServiceImpl<SampleParseTaskMapper, SampleParseTaskDO>
        implements SampleParseTaskDataService {

    @Override
    public SampleParseTaskDO findAliveBySample(long sampleId) {
        return getOne(new QueryWrapper<SampleParseTaskDO>()
                .eq("sample_id", sampleId)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    public long resetForRun(long sampleId, String mode) {
        SampleParseTaskDO row = findAliveBySample(sampleId);
        if (row == null) {
            row = new SampleParseTaskDO();
            row.setSampleId(sampleId);
            row.setMode(mode);
            row.setStatus("QUEUED");
            row.setTotalUnits(0);
            row.setDoneUnits(0);
            row.setStage("");
            row.setMessage(null);
            try {
                save(row);
                return row.getId();
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 「先查没有 → 再插入」之间的并发窗口：同一样本被同时点了两次解析（活跃唯一索引
                // uq_sample_parse_task_alive）。捕父类而不是 DuplicateKeyException——不让修复取决于
                // Spring 把 23505 翻成哪个子类；是否真的是并发插入，由「能不能重查到那一行」判定：
                // 查得到说明另一方刚插好，复用它；查不到（外键/非空等其它约束）原样往上抛。
                // 双跑本身由 runParse 的 QUEUED→RUNNING CAS 兜住，这里只是把状态重置回 QUEUED。
                row = findAliveBySample(sampleId);
                if (row == null) {
                    throw e;
                }
            }
        }
        update(new UpdateWrapper<SampleParseTaskDO>()
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
        return baseMapper.update(null, new UpdateWrapper<SampleParseTaskDO>()
                .eq("id", taskId)
                .eq("status", fromStatus)
                .set("status", toStatus));
    }

    @Override
    public void updateProgress(long taskId, int doneUnits, String stage) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDO>()
                .eq("id", taskId)
                .set("done_units", doneUnits)
                .set("stage", stage));
    }

    @Override
    public void updateTotal(long taskId, int totalUnits) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDO>()
                .eq("id", taskId)
                .set("total_units", totalUnits));
    }

    @Override
    public void finish(long taskId, String status, String stage, String message) {
        baseMapper.update(null, new UpdateWrapper<SampleParseTaskDO>()
                .eq("id", taskId)
                .set("status", status)
                .set("stage", stage)
                .set("message", message == null || message.isBlank() ? null
                        : message.substring(0, Math.min(message.length(), 256))));
    }
}
