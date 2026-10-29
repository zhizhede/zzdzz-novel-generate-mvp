package com.zzdzz.novelgen.llm.contract;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 节点输出契约注册表。
 *
 * <p>按 contract id 与 schema version 管理唯一契约，重复 key 会在注册时立即失败。
 */
@Component
public class NodeOutputContractRegistry {

    private final Map<String, NodeOutputContract<?>> contracts = new LinkedHashMap<>();

    /**
     * 注册节点输出契约。
     *
     * @param contract 待注册契约
     * @throws IllegalStateException 同一 contractId 与 schemaVersion 已注册
     */
    public void register(NodeOutputContract<?> contract) {
        String key = key(contract.contractId(), contract.schemaVersion());
        if (contracts.containsKey(key)) {
            throw new IllegalStateException("Duplicate node output contract key: " + key);
        }
        contracts.put(key, contract);
    }

    /**
     * 按契约标识与 schema 版本查找契约。
     *
     * @param contractId 契约标识
     * @param schemaVersion schema 版本
     * @return 已注册契约
     * @throws IllegalStateException 未注册对应契约
     */
    public NodeOutputContract<?> require(String contractId, int schemaVersion) {
        String key = key(contractId, schemaVersion);
        NodeOutputContract<?> contract = contracts.get(key);
        if (contract == null) {
            throw new IllegalStateException("Missing node output contract key: " + key);
        }
        return contract;
    }

    /**
     * 返回全部已注册契约，保持注册顺序。
     *
     * @return 契约集合
     */
    public Collection<NodeOutputContract<?>> all() {
        return List.copyOf(contracts.values());
    }

    private String key(String contractId, int schemaVersion) {
        return contractId + "@" + schemaVersion;
    }
}