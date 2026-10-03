package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.PipelineEventDO;

import java.util.List;

/** pipeline_events 数据服务接口（原 PipelineEventDAO）。 */
public interface PipelineEventDataService extends IService<PipelineEventDO> {

    record EventRow(long id, Integer chapterNo, String stage, String phase, String payloadJson, String createTime) {
    }

    void insert(Long novelId, Integer chapterNo, String stage, String phase, Object payload);

    List<EventRow> list(Long novelId, Integer chapterNo, int limit);
}
