package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO;
import org.apache.ibatis.annotations.Param;

/** outline_draft_tasks 表 Mapper：request 为 jsonb，写入在 resources/mapper/OutlineDraftTaskMapper.xml（::jsonb 转型）。 */
public interface OutlineDraftTaskMapper extends BaseMapper<OutlineDraftTaskDTO> {

    long insertTask(@Param("title") String title, @Param("request") String request, @Param("novelId") Long novelId);
}
