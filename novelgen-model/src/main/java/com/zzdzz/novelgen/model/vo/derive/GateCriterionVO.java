package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/**
 * P6 单项判据结果。
 *
 * @param key 判据稳定键
 * @param pass 是否通过
 * @param severity HARD / FIXABLE / ADVISORY
 * @param note 简短判断依据
 * @param issues 该判据命中的具体问题
 * @param repairTarget 建议回炉层级
 */
public record GateCriterionVO(
        String key,
        boolean pass,
        String severity,
        String note,
        List<String> issues,
        String repairTarget
) {
}
