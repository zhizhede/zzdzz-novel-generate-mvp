package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryObligationEvidenceDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** derive_story_obligation_evidence Mapper：JSONB 数组走 XML 显式 ::jsonb。 */
public interface DeriveStoryObligationEvidenceMapper extends BaseMapper<DeriveStoryObligationEvidenceDO> {

    int insertFull(@Param("p") DeriveStoryObligationEvidenceDO row);

    int deleteByRun(@Param("runId") long runId);

    int deleteByRunAndChapterRange(@Param("runId") long runId,
                                   @Param("fromChapter") int fromChapter,
                                   @Param("toChapter") int toChapter);

    List<DeriveStoryObligationEvidenceDO> selectByRun(@Param("runId") long runId);
}
