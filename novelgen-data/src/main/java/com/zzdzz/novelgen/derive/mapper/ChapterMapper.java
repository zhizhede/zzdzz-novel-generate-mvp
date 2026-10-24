package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import org.apache.ibatis.annotations.Param;

/** chapters（衍生域）Mapper：全标量列，标准 CRUD。 */
public interface ChapterMapper extends BaseMapper<ChapterDO> {

    /**
     * ⑧ REWORK 回炉：清正文回 PLANNED。
     * ⚠ 必须显式 SQL——MP updateById 的 NOT_NULL 策略跳过 null 字段，set content=null 不生效。
     */
    int clearForRewrite(@Param("novelId") long novelId);

    /** ⑥b REWORK 回炉（v3.7）：清 draft_outline 让分批窗从头重填（title 保留⑥a 章题）。 */
    int clearDraftOutlines(@Param("novelId") long novelId);

    /** 写入章功能槽合同。 */
    int updatePlanContract(@Param("chapterId") long chapterId, @Param("planContract") String planContract);

    /** 单卷重规划前清空该卷章功能合同。 */
    int clearPlanByVolume(@Param("novelId") long novelId, @Param("volumeNo") int volumeNo);

    int updateDraftOutline(@Param("id") long id, @Param("title") String title,
                           @Param("draftOutline") String draftOutline);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int updateContent(@Param("id") long id, @Param("content") String content,
                      @Param("charCount") int charCount, @Param("comment") String comment,
                      @Param("status") String status);
}
