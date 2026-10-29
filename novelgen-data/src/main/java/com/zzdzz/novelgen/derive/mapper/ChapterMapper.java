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

    /** PLAN_VOLUME 局部回炉：只清该卷 draft_outline，其余卷的具体稿保持不动。 */
    int clearDraftOutlinesByVolume(@Param("novelId") long novelId, @Param("volumeNo") int volumeNo);

    /** DRAFT 局部回炉：只清问题章的 draft_outline（其余章保留）。 */
    int clearDraftOutlinesByChapters(@Param("novelId") long novelId,
                                     @Param("chapterNos") java.util.List<Integer> chapterNos);

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

    /** 计划投影：插入一章计划骨架；plan_contract 留库默认，避免 MP 绑 varchar 写 jsonb。 */
    long insertPlanChapter(@Param("p") ChapterDO chapter);

    /** 计划投影：删除本书全部章节；调用方必须先拒绝任何非空正文。 */
    int deleteByNovelId(@Param("novelId") long novelId);
    /** 正文闭环：仅当前 novel+chapter_no+active plan revision 可写章节正文。 */
    int updateContentIfRevision(@Param("novelId") long novelId,
                                @Param("chapterNo") int chapterNo,
                                @Param("planRevision") int planRevision,
                                @Param("content") String content,
                                @Param("charCount") int charCount,
                                @Param("status") String status);

}
