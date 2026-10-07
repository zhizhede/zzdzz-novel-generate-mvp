package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 库实体公共基类：只放**每张表都有、且每个实体都要建模的列**——自增主键 + 创建/更新时间。
 * 30 个 XxxDO 一律继承它。
 *
 * <p>`createTime/updateTime` 抽出前是各实体手抄（20 个各自声明过、10 个没建模）。这两列从 Flyway V1
 * 起就是建表惯例，30 张业务表已逐表核过（`information_schema.columns`）。
 *
 * <p>**不放 `isDeleted`**：软删标记不进领域模型（2026-10-03 用户定调）。
 *
 * <p>**也不放 `deleteTime`（2026-10-03 软删下线后移除）**：删除已是物理删除，没有"删除时刻"可记。
 * 库里的 `is_deleted` / `delete_time` 两列作为死列保留（同日定调「列留库里当死列，只清代码」），
 * 实体不再映射它们。
 *
 * <p>**投影查询里这两列会保持 null**：只取部分列的 SQL 不会填充它们，用前先判空。
 */
@Data
public abstract class BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;
}
