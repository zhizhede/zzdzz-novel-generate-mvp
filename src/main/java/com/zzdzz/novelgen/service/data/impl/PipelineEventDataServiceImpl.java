package com.zzdzz.novelgen.service.data.impl;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.dao.PipelineEventMapper;
import com.zzdzz.novelgen.model.entity.PipelineEventDO;
import com.zzdzz.novelgen.service.data.PipelineEventDataService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** pipeline_events 数据服务实现。 */
@Service
@RequiredArgsConstructor
public class PipelineEventDataServiceImpl extends ServiceImpl<PipelineEventMapper, PipelineEventDO>
        implements PipelineEventDataService {

    private final ObjectMapper mapper;


    @Override
    public void insert(Long novelId, Integer chapterNo, String stage, String phase, Object payload) {
        try {
            baseMapper.insert(novelId, chapterNo, stage, phase,
                    payload == null ? "{}" : mapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("事件写入失败", e);
        }
    }

    @Override
    public List<EventRow> list(Long novelId, Integer chapterNo, int limit) {
        List<EventRow> out = new ArrayList<>();
        for (Map<String, Object> m : baseMapper.list(novelId, chapterNo, limit)) {
            out.add(new EventRow(((Number) m.get("id")).longValue(),
                    m.get("chapter_no") == null ? null : ((Number) m.get("chapter_no")).intValue(),
                    (String) m.get("stage"), (String) m.get("phase"), (String) m.get("payload"),
                    String.valueOf(m.get("create_time"))));
        }
        return out;
    }
}
