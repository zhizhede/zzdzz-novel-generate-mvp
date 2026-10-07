package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ForeshadowDO。 */
@Data
@TableName(value = "foreshadows")
public class ForeshadowDO extends BaseDO {
    private long novelId;
    private String code;
    private String content;
    private Integer plantedIn;
    private Integer recoveredIn;
    private Integer proposedIn;
    private String status;







}
