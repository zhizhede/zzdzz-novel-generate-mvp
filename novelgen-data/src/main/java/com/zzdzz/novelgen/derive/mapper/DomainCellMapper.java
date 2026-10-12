package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DomainCellDO;
import org.apache.ibatis.annotations.Param;

/**
 * domain_cell（衍生域）Mapper。
 *
 * <p>⚠ jsonb 列（cats/ref_ids）与 text[] 列（beat_types/counter_words）禁走 MP save()，
 * 写入一律 {@link #insertFull}（XML 显式 ::jsonb / ::text[]；text[] 字段存 PG 数组字面量 "{a,b}"）。
 * 自定义 SQL 在 resources/mapper/DeriveDomainCellMapper.xml。
 */
public interface DomainCellMapper extends BaseMapper<DomainCellDO> {

    /** 业务列全量插入（jsonb/text[] 显式 cast），返回新 id。 */
    long insertFull(@Param("p") DomainCellDO cell);
}
