package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.ImportAnalyzeTaskMapper;
import com.zzdzz.novelgen.model.dto.ImportAnalyzeTaskDTO;
import com.zzdzz.novelgen.service.data.ImportAnalyzeTaskDataService;
import org.springframework.stereotype.Service;

/** import_analyze_tasks 数据服务实现。 */
@Service
public class ImportAnalyzeTaskDataServiceImpl extends ServiceImpl<ImportAnalyzeTaskMapper, ImportAnalyzeTaskDTO>
        implements ImportAnalyzeTaskDataService {

    @Override
    public ImportAnalyzeTaskDTO findAliveByNovel(long novelId) {
        return getOne(new QueryWrapper<ImportAnalyzeTaskDTO>()
                .eq("novel_id", novelId)
                .eq("is_deleted", false)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    public long resetForRun(long novelId, String stepsJson) {
        ImportAnalyzeTaskDTO row = findAliveByNovel(novelId);
        if (row == null) {
            try {
                return baseMapper.insertTask(novelId, stepsJson);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 「先查没有 → 再插入」的并发窗口（双击「开始解析」）：活跃唯一索引
                // uq_import_analyze_task_alive 挡住第二次插入——复用在飞的那一行，不把键冲突抛给用户。
                row = findAliveByNovel(novelId);
                if (row == null) {
                    throw e;
                }
            }
        }
        baseMapper.resetTask(row.getId(), stepsJson);
        return row.getId();
    }

    @Override
    public void markCurrent(long taskId, String step) {
        update(new UpdateWrapper<ImportAnalyzeTaskDTO>()
                .eq("id", taskId)
                .set("status", "RUNNING")
                .set("current_step", step));
    }

    @Override
    public void appendStep(long taskId, String doneStepsJson) {
        baseMapper.updateDoneSteps(taskId, doneStepsJson);
    }

    @Override
    public void finish(long taskId, String status, String message) {
        update(new UpdateWrapper<ImportAnalyzeTaskDTO>()
                .eq("id", taskId)
                .set("status", status)
                .set("current_step", null)
                .set("message", message));
    }
}
