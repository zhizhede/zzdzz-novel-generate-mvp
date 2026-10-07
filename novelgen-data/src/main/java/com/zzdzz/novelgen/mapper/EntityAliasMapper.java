package com.zzdzz.novelgen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.EntityAliasDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** entity_aliases 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/EntityAliasMapper.xml。 */
public interface EntityAliasMapper extends BaseMapper<EntityAliasDO> {

    /** 整书重建的前半截：清掉本书旧索引。 */
    int deleteByNovel(@Param("novelId") long novelId);

    int insertRow(@Param("novelId") long novelId, @Param("cardId") long cardId, @Param("alias") String alias,
                  @Param("cardName") String cardName, @Param("cardKind") String cardKind,
                  @Param("primary") boolean primary);

    List<EntityAliasDO> listByNovel(@Param("novelId") long novelId);

    /** 反查：这个名字在本书里是谁的别名（无则 null）。 */
    EntityAliasDO findByAlias(@Param("novelId") long novelId, @Param("alias") String alias);
}
