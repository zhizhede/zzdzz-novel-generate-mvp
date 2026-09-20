package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.PromptTemplateDO;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** prompt_templates 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/PromptTemplateMapper.xml。 */
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateDO> {

    int updateContent(@Param("id") long id, @Param("content") String content);

    int reset(@Param("id") long id, @Param("content") String content, @Param("catalogHash") String catalogHash);

    List<PromptTemplateDO> findAll();

    PromptTemplateDO findById(@Param("id") long id);

    int syncUpdateStale(@Param("node") String node, @Param("phase") String phase, @Param("title") String title,
                        @Param("content") String content, @Param("exact") boolean exact, @Param("catalogHash") String catalogHash);

    int syncTouch(@Param("node") String node, @Param("phase") String phase);

    int syncInsertIfMissing(@Param("node") String node, @Param("phase") String phase, @Param("title") String title,
                            @Param("content") String content, @Param("exact") boolean exact, @Param("catalogHash") String catalogHash);

    List<PromptTemplateDataService.Reset> findNodePhase(@Param("id") long id);

}
