package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.model.dto.VolumeReviewDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** volume_reviews 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/VolumeReviewMapper.xml。 */
public interface VolumeReviewMapper extends BaseMapper<VolumeReviewDTO> {

    /** report 为已序列化 JSON 串（#{} 绑定不支持方法调用，JsonNode 在 DataService 层 toString——迁移期潜伏坑）。 */
    int upsert(@Param("novelId") long novelId, @Param("volNo") int volNo, @Param("report") String report);

    String findJson(@Param("novelId") long novelId, @Param("volNo") int volNo);
}
