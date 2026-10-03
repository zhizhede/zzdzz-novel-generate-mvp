package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
 * 库实体公共基类：只放**每张表都有的一列**——自增主键。30 个 XxxDO 一律继承它。
 *
 * <p>此前 30 个实体各自手写 `@TableId private Long id;`，加实体必抄、容易漏，抽出来一处可改。
 *
 * <p>**这里刻意不放 `isDeleted`（2026-10-03 用户定调）**：软删标记不进领域模型。
 * 也**不放时间戳**：`update_time` 由 SQL 内 `NOW()` 维护（见 docs/code-standards.md），
 * 进了基类后任何「查出来 → 改字段 → updateById」都会把旧值写回去覆盖 NOW()——MP 的更新策略是
 * NOT_NULL，非空字段都会进 SET 子句。软删条件仍由 DAO 的 SQL 自己带（XML 里 `is_deleted = FALSE`），
 * 不靠实体字段、也不靠 MP 的全局逻辑删除。
 */
@Data
public abstract class BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
}
