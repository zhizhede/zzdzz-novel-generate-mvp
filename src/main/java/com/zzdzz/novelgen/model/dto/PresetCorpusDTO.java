package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 品类语料章（preset_corpus）：用户随时导入、品类自由命名——特征提取管线的原料，量级不限。 */
@Data
@TableName(value = "preset_corpus")
public class PresetCorpusDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String genre;
    private String title;
    private String content;
    private Integer wordCount;
    private boolean isDeleted;
}
