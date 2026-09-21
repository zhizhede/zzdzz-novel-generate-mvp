package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.LlmModelPriceDTO;

import java.util.List;

/** llm_model_prices 数据服务接口（原 LlmModelPriceDAO）。 */
public interface LlmModelPriceDataService extends IService<LlmModelPriceDTO> {

    List<LlmModelPriceDTO> listAll();

    LlmModelPriceDTO findById(long id);

    int update(long id, java.math.BigDecimal idleInputHit, java.math.BigDecimal idleInputMiss,
               java.math.BigDecimal idleOutput, java.math.BigDecimal peakInputHit, java.math.BigDecimal peakInputMiss,
               java.math.BigDecimal peakOutput, int peakStartHour, int peakEndHour, String remark);
}
