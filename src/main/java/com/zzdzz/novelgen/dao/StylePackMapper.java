package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.StylePackDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** style_packs 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/StylePackMapper.xml。 */
public interface StylePackMapper extends BaseMapper<StylePackDO> {

    Long findIdByName(@Param("name") String name);

    long insert(@Param("name") String name, @Param("description") String description, @Param("rulesMd") String rulesMd, @Param("fingerprint") String fingerprint);

    int updateFingerprint(@Param("id") long id, @Param("fingerprint") String fingerprint);

    int updateRulesMdByNovel(@Param("novelId") long novelId, @Param("rulesMd") String rulesMd);

    String findGateConfigByNovel(@Param("novelId") long novelId);

    int updateGateConfigByNovel(@Param("novelId") long novelId, @Param("gateConfigJson") String gateConfigJson);

    String findRulesMdByNovel(@Param("novelId") long novelId);

    String findFingerprintByNovel(@Param("novelId") long novelId);
}
