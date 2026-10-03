package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.LlmProviderDO;

import java.util.List;

/** llm_providers 数据服务接口。 */
public interface LlmProviderDataService extends IService<LlmProviderDO> {

    /** 指定用途下启用中的接入（未软删且 enabled），按 id 升序——同用途多行启用时取首条。 */
    List<LlmProviderDO> listEnabled(String role);

    List<LlmProviderDO> listAll();

    LlmProviderDO findById(long id);

    LlmProviderDO findByName(String name);

    int softDelete(long id);

    /** 单活约束（按用途）：启用一条接入时，把同 role 的其它行置 disabled——会话与向量化各留一条。 */
    void disableAllOthersInRole(long keepId, String role);
}
