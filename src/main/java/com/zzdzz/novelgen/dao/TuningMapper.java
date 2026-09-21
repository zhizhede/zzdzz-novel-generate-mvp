package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.TuningDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** tuning 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/TuningMapper.xml。 */
public interface TuningMapper extends BaseMapper<TuningDTO> {

    List<TuningDTO> findAll();

    int updateValue(@Param("key") String key, @Param("value") String value);
}
