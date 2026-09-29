package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.OutlineDraftTaskMapper;
import com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO;
import com.zzdzz.novelgen.service.data.OutlineDraftTaskDataService;
import org.springframework.stereotype.Service;

/** AI 大纲草稿任务数据服务实现。 */
@Service
public class OutlineDraftTaskDataServiceImpl extends ServiceImpl<OutlineDraftTaskMapper, OutlineDraftTaskDTO>
        implements OutlineDraftTaskDataService {

    @Override
    public long insertTask(String title, String request, Long novelId) {
        return baseMapper.insertTask(title, request, novelId);
    }

    @Override
    public OutlineDraftTaskDTO latestByNovel(long novelId) {
        return getOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OutlineDraftTaskDTO>()
                .eq("novel_id", novelId)
                .eq("is_deleted", false)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    @Override
    public int casStatus(long taskId, String fromStatus, String toStatus) {
        return baseMapper.update(null, new UpdateWrapper<OutlineDraftTaskDTO>()
                .eq("id", taskId)
                .eq("status", fromStatus)
                .eq("is_deleted", false)
                .set("status", toStatus));
    }

    @Override
    public void finishDone(long taskId, String result) {
        baseMapper.update(null, new UpdateWrapper<OutlineDraftTaskDTO>()
                .eq("id", taskId)
                .eq("is_deleted", false)
                .set("status", "DONE")
                .set("result", result));
    }

    @Override
    public void finishFailed(long taskId, String message) {
        baseMapper.update(null, new UpdateWrapper<OutlineDraftTaskDTO>()
                .eq("id", taskId)
                .eq("is_deleted", false)
                .set("status", "FAILED")
                .set("message", message == null ? null
                        : message.substring(0, Math.min(message.length(), 256))));
    }
}
