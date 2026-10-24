package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.WorldStateDO;
import org.apache.ibatis.annotations.Param;

/**
 * world_states（衍生域）Mapper。⚠ jsonb 列禁走 MP save()：
 * {@link #upsert}（XML 显式 ::jsonb）。
 */
public interface WorldStateMapper extends BaseMapper<WorldStateDO> {

    /** ⑦ 落一章快照（同章重跑覆盖）。 */
    int upsert(@Param("p") WorldStateDO row);

    /** 取「本章之前」最近一份快照；无则 null。 */
    default WorldStateDO findLatestBefore(long novelId, int chapterNo) {
        return selectOne(new LambdaQueryWrapper<WorldStateDO>()
                .eq(WorldStateDO::getNovelId, novelId)
                .lt(WorldStateDO::getChapterNo, chapterNo)
                .orderByDesc(WorldStateDO::getChapterNo)
                .last("LIMIT 1"));
    }

    /** ⑥b REWORK 回炉：本书状态账整本清空（物理删）。 */
    int deleteByNovel(@Param("novelId") long novelId);
}
