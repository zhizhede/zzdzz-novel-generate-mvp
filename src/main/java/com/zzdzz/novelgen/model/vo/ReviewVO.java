package com.zzdzz.novelgen.model.vo;

import java.util.List;
import java.util.Map;

/** AI 语义审校结果：verdict ∈ pass|minor|blocker|skipped，issues 为审校问题明细。 */
public record ReviewVO(boolean passed, String verdict, String createTime,
                       String summary, List<Map<String, Object>> issues) {
}
