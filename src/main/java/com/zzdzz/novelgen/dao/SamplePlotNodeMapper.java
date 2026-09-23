package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;
import org.apache.ibatis.annotations.Param;

/** sample_plot_nodes 表 Mapper：beats/meta 为 jsonb，写入在 resources/mapper/SamplePlotNodeMapper.xml（::jsonb 转型）。 */
public interface SamplePlotNodeMapper extends BaseMapper<SamplePlotNodeDTO> {

    long insertNode(@Param("sampleId") long sampleId, @Param("level") String level, @Param("seq") int seq,
                    @Param("parentSeq") int parentSeq, @Param("title") String title, @Param("summary") String summary,
                    @Param("beats") String beats, @Param("meta") String meta);
}
