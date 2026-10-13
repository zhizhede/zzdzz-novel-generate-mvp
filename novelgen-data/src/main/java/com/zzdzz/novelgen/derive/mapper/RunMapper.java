package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.apache.ibatis.annotations.Param;

/**
 * run（衍生域）Mapper。⚠ jsonb 七列禁走 MP save()：
 * {@link #insertFull} 全量插入、{@link #updateWeld} 焊接后回填（均 XML 显式 ::jsonb）。
 */
public interface RunMapper extends BaseMapper<RunDO> {

    long insertFull(@Param("p") RunDO run);

    /** 5.3 焊接完成后回填：welded + status（SAMPLING→PLANNING）。 */
    int updateWeld(@Param("id") long id, @Param("welded") String welded);

    /** ⑥a 建成品书后回填 run.novel_id（status 不动，仍 PLANNING）。 */
    int updateNovelId(@Param("id") long id, @Param("novelId") long novelId);
}
