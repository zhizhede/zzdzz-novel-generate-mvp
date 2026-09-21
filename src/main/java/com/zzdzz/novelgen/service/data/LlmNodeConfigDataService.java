package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.LlmNodeConfigDTO;

import java.util.List;

/** llm_node_configs 数据服务接口（原 LlmNodeConfigDAO）。 */
public interface LlmNodeConfigDataService extends IService<LlmNodeConfigDTO> {

    LlmNodeConfigDTO findEnabled(String node);

    List<LlmNodeConfigDTO> listAll();

    LlmNodeConfigDTO findById(long id);

    boolean exists(String node);

    int softDelete(long id);

    long insert(String node, String model, Double temperature, Integer maxTokens,
                String extraJson, boolean enabled, String remark);

    void update(long id, String model, Double temperature, Integer maxTokens,
                String extraJson, boolean enabled, String remark);
}
