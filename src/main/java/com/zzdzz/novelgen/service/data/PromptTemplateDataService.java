package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.PromptTemplateDTO;

import java.util.List;

/** prompt_templates 数据服务接口（原 PromptTemplateDAO）。 */
public interface PromptTemplateDataService extends IService<PromptTemplateDTO> {

    int updateContent(long id, String content);

    int reset(long id, String content, String catalogHash);

    List<PromptTemplateDTO> findAll();

    java.util.Optional<PromptTemplateDTO> findById(long id);

    /** 目录同步：先插缺失，再更新未定制的过期行（内容变化 version+1），最后 touch。 */
    void sync(String node, String phase, String title, String content, boolean exact, String catalogHash);

    /** 目录同步三段：先插缺失，再更新未定制的过期行，最后 touch 已对齐行。 */
    int syncInsertIfMissing(String node, String phase, String title, String content, boolean exact, String catalogHash);

    int syncUpdateStale(String node, String phase, String title, String content, boolean exact, String catalogHash);

    int syncTouch(String node, String phase);

    record Reset(String node, String phase) {
    }

    java.util.Optional<Reset> findNodePhase(long id);

}
