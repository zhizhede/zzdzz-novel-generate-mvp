package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.StylePackDTO;

import java.util.List;

/** style_packs 数据服务接口（原 StylePackDAO）。 */
public interface StylePackDataService extends IService<StylePackDTO> {

    Long findIdByName(String name);

    long insert(String name, String description, String rulesMd, String fingerprint);

    int updateFingerprint(long id, String fingerprint);

    int updateRulesMdByNovel(long novelId, String rulesMd);

    String findGateConfigByNovel(long novelId);

    int updateGateConfigByNovel(long novelId, String gateConfigJson);

    String findRulesMdByNovel(long novelId);

    String findFingerprintByNovel(long novelId);
}
