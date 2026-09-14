package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmCallLogMapper;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** llm_call_log 数据服务实现。 */
@Service
public class LlmCallLogDataServiceImpl extends ServiceImpl<LlmCallLogMapper, LlmCallLogDO>
        implements LlmCallLogDataService {

    @Override
    public long insert(String node, Long novelId, Long chapterId, String model,
                       int promptTokens, int cachedTokens, int completionTokens, int totalTokens,
                       long latencyMs, String status, String errorMsg, String reasoningText,
                       String requestJson, String responseJson) {
        Long id = baseMapper.insert(node, novelId, chapterId, model, promptTokens, cachedTokens,
                completionTokens, totalTokens, latencyMs, status, errorMsg, reasoningText,
                requestJson, responseJson);
        return id == null ? 0 : id;
    }

    @Override
    public List<LlmCallLogDO> findPage(Long novelId, Long chapterId, int limit, int offset) {
        return baseMapper.findPage(novelId, chapterId, limit, offset);
    }

    @Override
    public LlmCallLogDO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public long countBy(Long novelId, Long chapterId) {
        Long n = baseMapper.countBy(novelId, chapterId);
        return n == null ? 0 : n;
    }

    @Override
    public Totals totalsBy(Long novelId, Long chapterId) {
        List<Map<String, Object>> rows = baseMapper.totalsBy(novelId, chapterId);
        Map<String, Object> m = rows.isEmpty() ? Map.of() : rows.get(0);
        return new Totals(((Number) m.getOrDefault("calls", 0)).longValue(),
                ((Number) m.getOrDefault("prompt_tokens", 0)).longValue(),
                ((Number) m.getOrDefault("completion_tokens", 0)).longValue(),
                ((Number) m.getOrDefault("total_tokens", 0)).longValue(),
                Math.round(((Number) m.getOrDefault("avg_latency", 0)).doubleValue()));
    }

    @Override
    public List<UsageGroup> usageByNodeSince(int days) {
        List<UsageGroup> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : baseMapper.usageByNodeSince(days)) {
            out.add(new UsageGroup((String) m.get("node"), (String) m.get("model"),
                    (Boolean) m.get("is_peak"), ((Number) m.get("calls")).longValue(),
                    ((Number) m.get("prompt_tokens")).longValue(),
                    ((Number) m.get("cached_tokens")).longValue(),
                    ((Number) m.get("completion_tokens")).longValue(),
                    ((Number) m.get("latency_sum")).longValue()));
        }
        return out;
    }
}
