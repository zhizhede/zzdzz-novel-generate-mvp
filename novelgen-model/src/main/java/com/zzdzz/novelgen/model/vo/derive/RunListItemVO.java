package com.zzdzz.novelgen.model.vo.derive;

import java.time.OffsetDateTime;

/** run 列表行（筛选展示；tags=param_tags 原文 json，intent=创作思路）。 */
public record RunListItemVO(
        long runId,
        String status,
        Integer qcRound,
        Long seed,
        Integer wordsMin,
        Integer wordsMax,
        String tags,
        String form,
        String intent,
        Boolean antiRepro,
        Long novelId,
        String novelTitle,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {
}
