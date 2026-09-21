package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.dto.VolumeReviewDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** volume_reviews 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/VolumeReviewMapper.xml。 */
public interface VolumeReviewMapper extends BaseMapper<VolumeReviewDTO> {

    int upsert(@Param("novelId") long novelId, @Param("volNo") int volNo, @Param("report") JsonNode report);

    String findJson(@Param("novelId") long novelId, @Param("volNo") int volNo);
}
