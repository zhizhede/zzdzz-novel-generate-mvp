package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.LlmProviderDTO;

import java.util.List;

/** llm_providers 数据服务接口。 */
public interface LlmProviderDataService extends IService<LlmProviderDTO> {

    /** 启用中的接入（未软删且 enabled），按 id 升序——多行启用时取首条为当前接入。 */
    List<LlmProviderDTO> listEnabled();

    List<LlmProviderDTO> listAll();

    LlmProviderDTO findById(long id);

    LlmProviderDTO findByName(String name);

    int softDelete(long id);

    /** 单活约束：把其它行全部置 disabled（启用一条接入时调用）。 */
    void disableAllOthers(long keepId);
}
