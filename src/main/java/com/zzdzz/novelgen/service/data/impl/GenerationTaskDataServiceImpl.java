package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.GenerationTaskMapper;
import com.zzdzz.novelgen.model.dto.GenerationTaskDTO;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** generation_tasks 数据服务实现。 */
@Service
public class GenerationTaskDataServiceImpl extends ServiceImpl<GenerationTaskMapper, GenerationTaskDTO>
        implements GenerationTaskDataService {

    @Override
    public long insert(long novelId, int fromChapter, int toChapter, Long submittedBy, String kind, String payload,
                       int priority) {
        return baseMapper.insert(novelId, fromChapter, toChapter, submittedBy, kind, payload, priority);
    }

    @Override
    public GenerationTaskDataService.TaskRow claimNextQueuedForDispatch() {
        Long queuedId = baseMapper.findQueuedForDispatch();
        if (queuedId == null) {
            return null;
        }
        int claimed = baseMapper.claim(queuedId);
        if (claimed == 0) {
            return null;
        }
        var rows = baseMapper.findRunningById(queuedId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean existsActiveForNovel(long novelId) {
        return baseMapper.existsActiveForNovel(novelId);
    }

    @Override
    public int countRunningNovels() {
        Integer n = baseMapper.countRunningNovels();
        return n == null ? 0 : n;
    }

    @Override
    public List<TaskRow> list(int limit) {
        return baseMapper.list(limit);
    }

    @Override
    public TaskRow claimNextQueued() {
        Long queuedId = baseMapper.findQueuedId();
        if (queuedId == null) {
            return null;
        }
        int claimed = baseMapper.claim(queuedId);
        if (claimed == 0) {
            return null;
        }
        var rows = baseMapper.findRunningById(queuedId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public TaskRow findRunning() {
        var rows = baseMapper.findRunning();
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<TaskRow> listRunning() {
        return baseMapper.listRunning();
    }

    @Override
    public void updateProgress(long id, int doneChapters, Integer currentChapter, String message) {
        baseMapper.updateProgress(id, doneChapters, currentChapter, message);
    }

    @Override
    public void updateStatus(long id, String status, String message) {
        baseMapper.updateStatus(id, status, message);
    }

    @Override
    public int cancelQueued(long id) {
        return baseMapper.cancelQueued(id);
    }

    @Override
    public String findStatus(long id) {
        return baseMapper.findStatus(id);
    }

    @Override
    public int resetInterrupted() {
        return baseMapper.resetInterrupted();
    }

    @Override
    public int cancelFlaggedOnRestart() {
        return baseMapper.cancelFlaggedOnRestart();
    }

    @Override
    public int requestCancel(long id) {
        return baseMapper.requestCancel(id);
    }

    @Override
    public boolean isCancelRequested(long id) {
        Boolean flag = baseMapper.isCancelRequested(id);
        return flag != null && flag;
    }

    @Override
    public boolean isPauseRequested(long id) {
        Boolean flag = baseMapper.isPauseRequested(id);
        return flag != null && flag;
    }

    @Override
    public int resumePaused(long id) {
        return baseMapper.resumePaused(id);
    }

    @Override
    public int requeueForRetry(long id, int fromChapter, int retryCount, String message) {
        return baseMapper.requeueForRetry(id, fromChapter, retryCount, message);
    }
}
