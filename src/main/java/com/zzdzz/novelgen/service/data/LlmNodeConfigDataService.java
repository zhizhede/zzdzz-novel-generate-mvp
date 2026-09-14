package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.LlmNodeConfigDO;

import java.util.List;

/** llm_node_configs 数据服务接口（原 LlmNodeConfigDAO）。 */
public interface LlmNodeConfigDataService extends IService<LlmNodeConfigDO> {

    LlmNodeConfigDO findEnabled(String node);

    List<LlmNodeConfigDO> listAll();

    LlmNodeConfigDO findById(long id);

    boolean exists(String node);

    int softDelete(long id);

    long insert(String node, String model, Double temperature, Integer maxTokens,
                String extraJson, boolean enabled, String remark);

    void update(long id, String model, Double temperature, Integer maxTokens,
                String extraJson, boolean enabled, String remark);
}
