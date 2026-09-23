package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;
import org.apache.ibatis.annotations.Param;

/** sample_cards 表 Mapper：aliases/relations 为 jsonb，写入在 resources/mapper/SampleCardMapper.xml（::jsonb 转型）。 */
public interface SampleCardMapper extends BaseMapper<SampleCardDTO> {

    long insertCard(@Param("sampleId") long sampleId, @Param("kind") String kind, @Param("name") String name,
                    @Param("aliases") String aliases, @Param("summary") String summary, @Param("contentMd") String contentMd,
                    @Param("relations") String relations, @Param("importance") int importance,
                    @Param("firstSeq") Integer firstSeq, @Param("mentions") int mentions);
}
