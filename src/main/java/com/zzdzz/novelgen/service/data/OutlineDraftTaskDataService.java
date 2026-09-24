package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO;

/** AI 大纲草稿任务数据服务。 */
public interface OutlineDraftTaskDataService extends IService<OutlineDraftTaskDTO> {

    /** 落任务行（QUEUED，request 为入参快照 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insertTask(String title, String request, Long novelId);

    /** 状态机抢占：仅当前状态等于 from 才更新。 */
    int casStatus(long taskId, String fromStatus, String toStatus);

    /** 成功收尾：状态 DONE + 结果正文。 */
    void finishDone(long taskId, String result);

    /** 失败收尾：状态 FAILED + 人话原因。 */
    void finishFailed(long taskId, String message);

    /** 某书最新一份大纲任务（草稿恢复用，无则 null）。 */
    OutlineDraftTaskDTO latestByNovel(long novelId);
}
