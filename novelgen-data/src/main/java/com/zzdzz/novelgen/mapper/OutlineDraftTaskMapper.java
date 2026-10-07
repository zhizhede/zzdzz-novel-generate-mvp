package com.zzdzz.novelgen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.OutlineDraftTaskDO;
import org.apache.ibatis.annotations.Param;

/** outline_draft_tasks 表 Mapper：request 为 jsonb，写入在 resources/mapper/OutlineDraftTaskMapper.xml（::jsonb 转型）。 */
public interface OutlineDraftTaskMapper extends BaseMapper<OutlineDraftTaskDO> {

    long insertTask(@Param("title") String title, @Param("request") String request, @Param("novelId") Long novelId);
}
