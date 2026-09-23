package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.ImportedSampleDTO;
import org.apache.ibatis.annotations.Param;

/** imported_samples 表 Mapper：查询走 BaseMapper；jsonb 写入在 resources/mapper/ImportedSampleMapper.xml（::jsonb 转型）。 */
public interface ImportedSampleMapper extends BaseMapper<ImportedSampleDTO> {

    long insert(@Param("title") String title, @Param("genre") String genre, @Param("chunks") int chunks,
                @Param("totalChars") long totalChars, @Param("source") String source,
                @Param("analysis") String analysis);

    /** jsonb 列更新必须走 XML ::jsonb 转型（UpdateWrapper 直插撞 varchar 类型错，本仓库已知坑）。 */
    int updateTags(@Param("sampleId") long sampleId, @Param("tagsJson") String tagsJson);
}
