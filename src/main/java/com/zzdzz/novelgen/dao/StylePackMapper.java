package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** style_packs 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/StylePackMapper.xml。 */
public interface StylePackMapper extends BaseMapper<StylePackDTO> {

    Long findIdByName(@Param("name") String name);

    long insert(@Param("name") String name, @Param("description") String description, @Param("rulesMd") String rulesMd, @Param("fingerprint") String fingerprint);

    int updateFingerprint(@Param("id") long id, @Param("fingerprint") String fingerprint);

    int updateRulesMdByNovel(@Param("novelId") long novelId, @Param("rulesMd") String rulesMd);

    String findGateConfigByNovel(@Param("novelId") long novelId);

    int updateGateConfigByNovel(@Param("novelId") long novelId, @Param("gateConfigJson") String gateConfigJson);

    String findRulesMdByNovel(@Param("novelId") long novelId);

    String findFingerprintByNovel(@Param("novelId") long novelId);

    /** 预设按 id 读门禁配置（预设不被书引用，走不了 novel 联查）。 */
    String findGateConfigById(@Param("id") long id);

    /** 预设落库（is_preset=TRUE，gate_config 直存），返回 id。 */
    long insertPreset(@Param("name") String name, @Param("description") String description,
                      @Param("rulesMd") String rulesMd, @Param("fingerprint") String fingerprint,
                      @Param("gateConfig") String gateConfig);

    /** 开书克隆：复制预设的指纹/门禁/规则为书的私有风格包（is_preset=FALSE），返回 id。 */
    long insertPack(@Param("name") String name, @Param("description") String description,
                    @Param("rulesMd") String rulesMd, @Param("fingerprint") String fingerprint,
                    @Param("gateConfig") String gateConfig);
}
