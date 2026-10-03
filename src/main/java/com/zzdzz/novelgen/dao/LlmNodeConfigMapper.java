package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.LlmNodeConfigDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** llm_node_configs 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/LlmNodeConfigMapper.xml。 */
public interface LlmNodeConfigMapper extends BaseMapper<LlmNodeConfigDO> {

    LlmNodeConfigDO findEnabled(@Param("node") String node);

    List<LlmNodeConfigDO> listAll();

    LlmNodeConfigDO findById(@Param("id") long id);

    boolean exists(@Param("node") String node);

    int softDelete(@Param("id") long id);

    Long insert(@Param("node") String node, @Param("model") String model, @Param("temperature") Double temperature,
                @Param("maxTokens") Integer maxTokens, @Param("extraJson") String extraJson,
                @Param("enabled") boolean enabled, @Param("remark") String remark);

    int update(@Param("id") long id, @Param("model") String model, @Param("temperature") Double temperature,
               @Param("maxTokens") Integer maxTokens, @Param("extraJson") String extraJson,
               @Param("enabled") boolean enabled, @Param("remark") String remark);
}
