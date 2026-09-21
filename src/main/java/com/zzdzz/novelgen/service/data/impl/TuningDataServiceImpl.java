package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.TuningMapper;
import com.zzdzz.novelgen.model.dto.TuningDTO;
import com.zzdzz.novelgen.service.data.TuningDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** tuning 数据服务实现。 */
@Service
public class TuningDataServiceImpl extends ServiceImpl<TuningMapper, TuningDTO> implements TuningDataService {

    @Override
    public List<TuningDTO> findAll() {
        return baseMapper.findAll();
    }

    @Override
    public int updateValue(String key, String value) {
        return baseMapper.updateValue(key, value);
    }
}
