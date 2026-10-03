package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.LlmCallLogDO;
import com.zzdzz.novelgen.service.data.LlmCallLogDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** llm_call_log 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/LlmCallLogMapper.xml。 */
public interface LlmCallLogMapper extends BaseMapper<LlmCallLogDO> {

    Long insert(@Param("node") String node, @Param("novelId") Long novelId, @Param("chapterId") Long chapterId,
                @Param("model") String model, @Param("promptTokens") int promptTokens,
                @Param("cachedTokens") int cachedTokens, @Param("completionTokens") int completionTokens,
                @Param("totalTokens") int totalTokens, @Param("latencyMs") long latencyMs,
                @Param("status") String status, @Param("errorMsg") String errorMsg,
                @Param("reasoningText") String reasoningText, @Param("requestJson") String requestJson,
                @Param("responseJson") String responseJson);

    List<LlmCallLogDO> findPage(@Param("novelId") Long novelId, @Param("chapterId") Long chapterId,
                                @Param("limit") int limit, @Param("offset") int offset);

    LlmCallLogDO findById(@Param("id") long id);

    Long countBy(@Param("novelId") Long novelId, @Param("chapterId") Long chapterId);

    List<LlmCallLogDataService.Totals> totalsBy(@Param("novelId") Long novelId, @Param("chapterId") Long chapterId);

    List<LlmCallLogDataService.UsageGroup> usageByNodeSince(@Param("days") int days);
}
