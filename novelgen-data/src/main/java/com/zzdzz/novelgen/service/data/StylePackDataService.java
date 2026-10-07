package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.StylePackDO;

import java.util.List;

/** style_packs 数据服务接口（原 StylePackDAO）。 */
public interface StylePackDataService extends IService<StylePackDO> {

    Long findIdByName(String name);

    long insert(String name, String description, String rulesMd, String fingerprint);

    int updateFingerprint(long id, String fingerprint);

    int updateRulesMdByNovel(long novelId, String rulesMd);

    String findGateConfigByNovel(long novelId);

    int updateGateConfigByNovel(long novelId, String gateConfigJson);

    String findRulesMdByNovel(long novelId);

    String findFingerprintByNovel(long novelId);

    // ===== 题材预设（阶段三）：预设 = is_preset 风格包，应用到书 = 拷贝字段 =====

    /** 预设按 id 读门禁配置（预设不被书引用）。 */
    String findGateConfigById(long id);

    /** 预设列表。 */
    java.util.List<StylePackDO> listPresets();

    /** 预设落库（is_preset=TRUE），返回 id。 */
    long insertPreset(String name, String description, String rulesMd, String fingerprint, String gateConfig);

    /** 开书克隆：复制预设为书的私有风格包（is_preset=FALSE），返回 id。 */
    long insertPack(String name, String description, String rulesMd, String fingerprint, String gateConfig);

    /** 同名可复用包（非预设；无任何书引用的孤儿包），无则 null。 */
    Long findReusablePackId(String name);

    /** 复用包：整包字段原地改写（孤儿包转成在用的包）。 */
    int reusePack(long id, String name, String description, String rulesMd, String fingerprint, String gateConfig);

    /** 删书级联：包若无其他书引用则物理删除（须在删书之后、用删书前取出的 packId 调用）。 */
    int deleteOrphanPack(long packId);
}
