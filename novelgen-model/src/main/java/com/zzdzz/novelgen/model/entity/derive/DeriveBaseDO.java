package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 衍生系统库实体公共基类（表结构设计-v1.2 §1 共用字段）：
 * id + created_time/updated_time + created_user_id/updated_user_id。
 *
 * <p>与 zzdzz 的 {@code entity.BaseDO} 列名不同（created_* vs create_*，v1.2 规定），
 * 故独立基类、不复用。无 is_deleted（软删已下线，删除=物理删除）。
 *
 * <p>注意 MP 更新策略 NOT_NULL：「查出来→改字段→updateById」会把旧 created_time 写回，
 * 更新路径要么 setSql("updated_time=NOW()")，要么只更新业务字段（同 zzdzz BaseDO 既有坑）。
 */
@Data
public abstract class DeriveBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdTime;

    private OffsetDateTime updatedTime;

    private Long createdUserId;

    private Long updatedUserId;
}
