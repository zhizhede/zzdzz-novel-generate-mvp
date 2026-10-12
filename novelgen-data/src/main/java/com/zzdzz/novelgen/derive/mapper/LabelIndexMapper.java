package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.LabelIndexDO;
import org.apache.ibatis.annotations.Param;

/**
 * label_index（衍生域）Mapper——标签单一事实源。
 *
 * <p>⚠ jsonb 两列（cat/arc_fit）禁走 MP save()，写入一律 {@link #insertFull}（XML 显式 ::jsonb）。
 * 自定义 SQL 在 resources/mapper/DeriveLabelIndexMapper.xml。
 */
public interface LabelIndexMapper extends BaseMapper<LabelIndexDO> {

    /** 业务列全量插入（jsonb 显式 cast），返回新 id。 */
    long insertFull(@Param("p") LabelIndexDO label);
}
