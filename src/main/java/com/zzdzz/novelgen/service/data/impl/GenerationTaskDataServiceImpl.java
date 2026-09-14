package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.GenerationTaskMapper;
import com.zzdzz.novelgen.model.entity.GenerationTaskDO;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** generation_tasks 数据服务实现。 */
@Service
public class GenerationTaskDataServiceImpl extends ServiceImpl<GenerationTaskMapper, GenerationTaskDO>
        implements GenerationTaskDataService {

    private TaskRow toRow(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new TaskRow(((Number) m.get("id")).longValue(),
                ((Number) m.get("novel_id")).longValue(),
                (String) m.get("title"),
                ((Number) m.get("from_chapter")).intValue(),
                ((Number) m.get("to_chapter")).intValue(),
                (String) m.get("status"),
                ((Number) m.get("done_chapters")).intValue(),
                m.get("current_chapter") == null ? null : ((Number) m.get("current_chapter")).intValue(),
                (String) m.get("last_message"),
                String.valueOf(m.get("create_time")));
    }

    @Override
    public long insert(long novelId, int fromChapter, int toChapter, Long submittedBy) {
        return baseMapper.insert(novelId, fromChapter, toChapter, submittedBy);
    }

    @Override
    public List<TaskRow> list(int limit) {
        List<TaskRow> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : baseMapper.list(limit)) {
            out.add(toRow(m));
        }
        return out;
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
        List<Map<String, Object>> rows = baseMapper.findRunningById(queuedId);
        return toRow(rows.isEmpty() ? null : rows.get(0));
    }

    @Override
    public TaskRow findRunning() {
        List<Map<String, Object>> rows = baseMapper.findRunning();
        return toRow(rows.isEmpty() ? null : rows.get(0));
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
}
