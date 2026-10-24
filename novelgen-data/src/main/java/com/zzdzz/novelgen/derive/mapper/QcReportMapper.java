package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.QcReportDO;
import org.apache.ibatis.annotations.Param;

/**
 * qc_report（衍生域）Mapper。⚠ jsonb 四列禁走 MP save()：
 * {@link #insertFull}（XML 显式 ::jsonb）。
 */
public interface QcReportMapper extends BaseMapper<QcReportDO> {

    /** ⑧落一行质检报告（jsonb 显式 cast；created_* 走库默认），返回新 id。 */
    long insertFull(@Param("p") QcReportDO report);
}
