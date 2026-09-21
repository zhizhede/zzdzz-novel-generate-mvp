package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.LlmModelPriceDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** llm_model_prices 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/LlmModelPriceMapper.xml。 */
public interface LlmModelPriceMapper extends BaseMapper<LlmModelPriceDTO> {

    List<LlmModelPriceDTO> listAll();

    LlmModelPriceDTO findById(@Param("id") long id);

    int update(@Param("id") long id, @Param("idleInputHit") java.math.BigDecimal idleInputHit,
               @Param("idleInputMiss") java.math.BigDecimal idleInputMiss, @Param("idleOutput") java.math.BigDecimal idleOutput,
               @Param("peakInputHit") java.math.BigDecimal peakInputHit, @Param("peakInputMiss") java.math.BigDecimal peakInputMiss,
               @Param("peakOutput") java.math.BigDecimal peakOutput, @Param("peakStartHour") int peakStartHour,
               @Param("peakEndHour") int peakEndHour, @Param("remark") String remark);
}
