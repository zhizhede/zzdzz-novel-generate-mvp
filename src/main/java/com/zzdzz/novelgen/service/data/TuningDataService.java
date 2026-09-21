package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.TuningDTO;

import java.util.List;

/** tuning 数据服务接口（原 TuningDAO）。 */
public interface TuningDataService extends IService<TuningDTO> {

    List<TuningDTO> findAll();

    int updateValue(String key, String value);
}
