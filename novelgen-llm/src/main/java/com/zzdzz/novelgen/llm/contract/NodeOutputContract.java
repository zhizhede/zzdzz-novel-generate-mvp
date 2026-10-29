package com.zzdzz.novelgen.llm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Function;

/**
 * 节点级 LLM 输出契约。
 *
 * @param <T> 解析后的业务类型
 * @param contractId 契约标识
 * @param node 节点标识
 * @param schemaVersion schema 版本
 * @param schemaView JSON Schema 视图
 * @param promptFragment 追加给模型的契约提示片段
 * @param parser JSON 到业务类型的解析器
 * @param domainValidator 领域校验器
 */
public record NodeOutputContract<T>(
        String contractId,
        String node,
        int schemaVersion,
        JsonNode schemaView,
        String promptFragment,
        Function<JsonNode, T> parser,
        Function<T, List<ContractViolation>> domainValidator) {
}
