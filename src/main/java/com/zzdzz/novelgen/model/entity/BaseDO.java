package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 库实体公共基类：只放**每张表都有、且每个实体都要建模的列**——自增主键 + 时间三件套。
 * 30 个 XxxDO 一律继承它。
 *
 * <p>`createTime/updateTime/deleteTime` 抽出前是各实体手抄：20 个各自声明过、10 个干脆没建模。
 * 这三列从 Flyway V1 起就是建表惯例，30 张业务表已逐表核过（information_schema.columns）。
 *
 * <p>**这里刻意不放 `isDeleted`（2026-10-03 用户定调）**：软删标记不进领域模型。
 * 软删条件**只**由 DAO 的 SQL 自己带（XML 里 `is_deleted = FALSE`，这是唯一过滤点），
 * 不靠实体字段、也不靠 MP 的全局逻辑删除。
 *
 * <p>**时间戳进基类的代价（知悉并接受）**：`update_time` 由 SQL 内 `NOW()` 维护，应用不手传；
 * MP 的更新策略是 NOT_NULL，所以任何「查出来 → 改字段 → updateById」都会把读到的旧
 * `update_time` 写回 SET，覆盖 `now()`（表上无触发器，落库即旧值）。现存 updateById 调用点
 * 只有两处：`ChapterStepDataServiceImpl.finish`（新建 patch，时间列全 null → 不进 SET，安全）
 * 与 `LlmProviderService.update`（读到实体 → 旧值写回，该行 `update_time` 不前进，属既有问题）。
 *
 * <p>**投影查询里这三列会保持 null**：只取部分列的 SQL（如规划资产页的 content-only 查询）
 * 不会填充它们，用前先判空。
 */
@Data
public abstract class BaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createTime;

    private OffsetDateTime updateTime;

    private OffsetDateTime deleteTime;
}
