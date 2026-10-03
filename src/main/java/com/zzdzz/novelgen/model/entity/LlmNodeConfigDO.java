package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** LLM 节点路由配置：node 口径的模型/参数覆盖（平台级）。任一字段为空=该项走调用方/全局默认。 */
@Data
@TableName(value = "llm_node_configs")
public class LlmNodeConfigDO extends BaseDO {
    private String node;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String extraJson;
    private boolean enabled;
    private String remark;







    @Deprecated
    public boolean enabled() {
        return isEnabled();
    }

}
