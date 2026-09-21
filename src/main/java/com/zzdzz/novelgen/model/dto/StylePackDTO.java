package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** StylePackDTO。 */
@Data
@TableName(value = "style_packs")
public class StylePackDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String rulesMd;
    private String fingerprint;
    private boolean isDeleted;





}
