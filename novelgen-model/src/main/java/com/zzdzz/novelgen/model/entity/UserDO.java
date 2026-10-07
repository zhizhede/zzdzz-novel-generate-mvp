package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** UserDO。 */
@Data
@TableName(value = "users")
public class UserDO extends BaseDO {
    private String username;
    private String passwordHash;
    private String role;
}
