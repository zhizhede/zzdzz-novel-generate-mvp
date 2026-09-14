package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.GenerationTaskDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** generation_tasks 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/GenerationTaskMapper.xml。 */
public interface GenerationTaskMapper extends BaseMapper<GenerationTaskDO> {

    Long insert(@Param("novelId") long novelId, @Param("fromChapter") int fromChapter,
                @Param("toChapter") int toChapter, @Param("submittedBy") Long submittedBy);

    List<Map<String, Object>> list(@Param("limit") int limit);

    Long findQueuedId();

    int claim(@Param("id") long id);

    List<Map<String, Object>> findRunning();

    List<Map<String, Object>> findRunningById(@Param("id") long id);

    int updateProgress(@Param("id") long id, @Param("doneChapters") int doneChapters,
                       @Param("currentChapter") Integer currentChapter, @Param("message") String message);

    int updateStatus(@Param("id") long id, @Param("status") String status, @Param("message") String message);

    int cancelQueued(@Param("id") long id);

    String findStatus(@Param("id") long id);

    int resetInterrupted();
}
