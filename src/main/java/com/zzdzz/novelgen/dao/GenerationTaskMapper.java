package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.GenerationTaskDTO;
import com.zzdzz.novelgen.service.data.GenerationTaskDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** generation_tasks 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/GenerationTaskMapper.xml。 */
public interface GenerationTaskMapper extends BaseMapper<GenerationTaskDTO> {

    Long insert(@Param("novelId") long novelId, @Param("fromChapter") int fromChapter,
                @Param("toChapter") int toChapter, @Param("submittedBy") Long submittedBy,
                @Param("kind") String kind, @Param("payload") String payload,
                @Param("priority") int priority);

    boolean existsActiveForNovel(@Param("novelId") long novelId);

    List<GenerationTaskDataService.TaskRow> list(@Param("limit") int limit);

    Long findQueuedId();

    int claim(@Param("id") long id);

    List<GenerationTaskDataService.TaskRow> findRunning();

    List<GenerationTaskDataService.TaskRow> listRunning();

    Long findQueuedForDispatch();

    int countRunningNovels();

    List<GenerationTaskDataService.TaskRow> findRunningById(@Param("id") long id);

    int updateProgress(@Param("id") long id, @Param("doneChapters") int doneChapters,
                       @Param("currentChapter") Integer currentChapter, @Param("message") String message);

    int updateStatus(@Param("id") long id, @Param("status") String status, @Param("message") String message);

    int cancelQueued(@Param("id") long id);

    String findStatus(@Param("id") long id);

    int resetInterrupted();

    int cancelFlaggedOnRestart();

    int requestCancel(@Param("id") long id);

    Boolean isCancelRequested(@Param("id") long id);

    Boolean isPauseRequested(@Param("id") long id);

    /** 插队暂停后继续：PAUSED→QUEUED，从暂停点下一章续跑。 */
    int resumePaused(@Param("id") long id);

    /** 失败自动重试：RUNNING→QUEUED 原子翻转，从失败章断点续跑（守卫 RUNNING 且未请求取消）。 */
    int requeueForRetry(@Param("id") long id, @Param("fromChapter") int fromChapter,
                        @Param("retryCount") int retryCount, @Param("message") String message);
}
