package com.zzdzz.novelgen.model.entity;

/** LLM 节点路由配置：node 口径的模型/参数覆盖（平台级）。任一字段为空=该项走调用方/全局默认。 */
public record LlmNodeConfigDO(Long id, String node, String model, Double temperature, Integer maxTokens,
                              String extraJson, boolean enabled, String remark, boolean isDeleted) {
}
