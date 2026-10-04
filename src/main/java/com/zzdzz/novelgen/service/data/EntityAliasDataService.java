package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.EntityAliasDO;

import java.util.List;

/** entity_aliases 数据服务接口：别名索引的读写（派生自 material_cards，见 EntityAliasService）。 */
public interface EntityAliasDataService extends IService<EntityAliasDO> {

    /** 一行别名（cardName/cardKind 冗余存，反查一步到位）。 */
    record Row(long cardId, String alias, String cardName, String cardKind, boolean primary) {
    }

    /** 整书重建（先删后插，幂等）。 */
    void rebuild(long novelId, List<Row> rows);

    List<EntityAliasDO> listByNovel(long novelId);

    EntityAliasDO findByAlias(long novelId, String alias);
}
