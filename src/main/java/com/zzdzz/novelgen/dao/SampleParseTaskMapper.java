package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.SampleParseTaskDTO;

/** sample_parse_tasks 表 Mapper：全标量列，走 BaseMapper 即可（无 jsonb）。 */
public interface SampleParseTaskMapper extends BaseMapper<SampleParseTaskDTO> {
}
