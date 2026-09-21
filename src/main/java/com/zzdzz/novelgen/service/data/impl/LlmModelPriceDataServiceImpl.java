package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmModelPriceMapper;
import com.zzdzz.novelgen.model.dto.LlmModelPriceDTO;
import com.zzdzz.novelgen.service.data.LlmModelPriceDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** llm_model_prices 数据服务实现。 */
@Service
public class LlmModelPriceDataServiceImpl extends ServiceImpl<LlmModelPriceMapper, LlmModelPriceDTO> implements LlmModelPriceDataService {

    @Override
    public List<LlmModelPriceDTO> listAll() {
        return baseMapper.listAll();
    }

    @Override
    public LlmModelPriceDTO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public int update(long id, java.math.BigDecimal idleInputHit, java.math.BigDecimal idleInputMiss,
                      java.math.BigDecimal idleOutput, java.math.BigDecimal peakInputHit, java.math.BigDecimal peakInputMiss,
                      java.math.BigDecimal peakOutput, int peakStartHour, int peakEndHour, String remark) {
        return baseMapper.update(id, idleInputHit, idleInputMiss, idleOutput, peakInputHit, peakInputMiss,
                peakOutput, peakStartHour, peakEndHour, remark);
    }
}
