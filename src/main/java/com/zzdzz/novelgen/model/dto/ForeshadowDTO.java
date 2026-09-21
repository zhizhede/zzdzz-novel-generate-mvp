package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ForeshadowDTO。 */
@Data
@TableName(value = "foreshadows")
public class ForeshadowDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long novelId;
    private String code;
    private String content;
    private Integer plantedIn;
    private Integer recoveredIn;
    private Integer proposedIn;
    private String status;
    private boolean isDeleted;








}
