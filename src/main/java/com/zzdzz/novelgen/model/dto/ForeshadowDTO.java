package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ForeshadowDTO。 */
@Data
@TableName(value = "foreshadows")
public class ForeshadowDTO extends BaseDTO {
    private long novelId;
    private String code;
    private String content;
    private Integer plantedIn;
    private Integer recoveredIn;
    private Integer proposedIn;
    private String status;







}
