package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.ImportedSampleDTO;

/** 导入小说样本台账数据服务。 */
public interface ImportedSampleDataService extends IService<ImportedSampleDTO> {

    /** 按导入时间倒序列出未删样本。 */
    java.util.List<ImportedSampleDTO> listAlive();

    /** 台账落行（analysis 为完整分析快照 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insert(String title, String genre, int chunks, long totalChars, String source, String analysis);

    /** 品类采纳为预设后回链：该品类全部样本记下 presetId。 */
    int linkPreset(String genre, long presetId);

    /** 样本标签更新（AI 提取后写回；tags 为 JSON 数组文本，XML 内 ::jsonb 转型）。 */
    int updateTags(long sampleId, String tagsJson);
}
