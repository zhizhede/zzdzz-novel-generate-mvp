package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.mapper.EntityAliasMapper;
import com.zzdzz.novelgen.model.entity.EntityAliasDO;
import com.zzdzz.novelgen.service.data.EntityAliasDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** entity_aliases 数据服务实现。 */
@Service
public class EntityAliasDataServiceImpl extends ServiceImpl<EntityAliasMapper, EntityAliasDO>
        implements EntityAliasDataService {

    @Override
    public void rebuild(long novelId, List<Row> rows) {
        baseMapper.deleteByNovel(novelId);
        for (Row r : rows) {
            baseMapper.insertRow(novelId, r.cardId(), r.alias(), r.cardName(), r.cardKind(), r.primary());
        }
    }

    @Override
    public List<EntityAliasDO> listByNovel(long novelId) {
        return baseMapper.listByNovel(novelId);
    }

    @Override
    public EntityAliasDO findByAlias(long novelId, String alias) {
        return baseMapper.findByAlias(novelId, alias);
    }
}
