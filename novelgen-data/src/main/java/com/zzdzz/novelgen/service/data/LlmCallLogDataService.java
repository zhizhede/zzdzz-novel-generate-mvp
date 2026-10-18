package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;

import java.util.List;

/** llm_call_log 数据服务接口（原 LlmCallLogDAO）。 */
public interface LlmCallLogDataService extends IService<LlmCallLogDO> {

    record Totals(long calls, long promptTokens, long completionTokens, long totalTokens, long avgLatencyMs) {
    }

    record UsageGroup(String node, String model, boolean peak, long calls, long promptTokens,
                      long cachedTokens, long completionTokens, long latencySum) {
    }

    long insert(String node, Long novelId, Long chapterId, Long runId, String model,
                int promptTokens, int cachedTokens, int completionTokens, int totalTokens,
                long latencyMs, String status, String errorMsg, String reasoningText,
                String requestJson, String responseJson);

    List<LlmCallLogDO> findPage(Long novelId, Long chapterId, int limit, int offset);

    /**
     * 按衍生 run 取调用流水。历史行（run_id 列上线前）把 run id 写进了 novel_id，
     * 故一并按 novel_id=runId 兜住，保证老 run 的输入输出仍可回放。
     */
    List<LlmCallLogDO> findPageByRun(Long runId, String nodePrefix, int limit, int offset);

    long countByRun(Long runId, String nodePrefix);

    LlmCallLogDO findById(long id);

    long countBy(Long novelId, Long chapterId);

    Totals totalsBy(Long novelId, Long chapterId);

    List<UsageGroup> usageByNodeSince(int days);
}
