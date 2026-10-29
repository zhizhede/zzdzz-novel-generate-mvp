package com.zzdzz.novelgen.derive.plan;

import com.zzdzz.novelgen.llm.contract.NodeOutputContractRegistry;
import org.springframework.context.annotation.Configuration;

/** 注册故事规划 V2 已交付的节点输出契约。 */
@Configuration
public class PlanContractConfiguration {

    /**
     * 注册 ROUTE v2 契约。
     *
     * @param registry 节点输出契约注册表
     */
    public PlanContractConfiguration(NodeOutputContractRegistry registry) {
        registry.register(PlanContractSchemas.routeNarrative());
    }
}