package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.ImportAnalyzeTaskDO;
import org.apache.ibatis.annotations.Param;

/** import_analyze_tasks 表 Mapper：两个 JSONB 列走 XML 显式 ::jsonb（MP 自动绑 varchar 塞不进 jsonb）。 */
public interface ImportAnalyzeTaskMapper extends BaseMapper<ImportAnalyzeTaskDO> {

    /** 新建任务行，返回 id。 */
    long insertTask(@Param("novelId") long novelId, @Param("stepsJson") String stepsJson);

    /** 重置活跃任务行（重跑：清空逐步结果与消息）。 */
    int resetTask(@Param("id") long id, @Param("stepsJson") String stepsJson);

    /** 覆盖逐步结果（每步跑完调一次，作为断点事实）。 */
    int updateDoneSteps(@Param("id") long id, @Param("doneStepsJson") String doneStepsJson);
}
