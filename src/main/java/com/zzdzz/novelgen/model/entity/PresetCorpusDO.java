package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 品类语料章（preset_corpus）：用户随时导入、品类自由命名——特征提取管线的原料，量级不限。 */
@Data
@TableName(value = "preset_corpus")
public class PresetCorpusDO extends BaseDO {
    private String genre;
    private String title;
    private String content;
    private Integer wordCount;
}
