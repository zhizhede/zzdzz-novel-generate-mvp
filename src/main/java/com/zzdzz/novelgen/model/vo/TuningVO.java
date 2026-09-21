package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.entity.TuningDO;

/** 调参行（平台级行为参数）：软删除内部字段不出 API。 */
public record TuningVO(Long id, String key, String value, String description) {

    public static TuningVO from(TuningDO d) {
        return new TuningVO(d.getId(), d.getKey(), d.getValue(), d.getDescription());
    }
}
