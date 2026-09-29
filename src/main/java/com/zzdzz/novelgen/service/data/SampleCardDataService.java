package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;

import java.util.List;

/** 导入样本结构化资产卡数据服务。 */
public interface SampleCardDataService extends IService<SampleCardDTO> {

    /** 该样本全部活跃卡（按 importance 降序、id 升序）。 */
    List<SampleCardDTO> listBySample(long sampleId);

    /** 落卡（aliases/relations 为 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insertCard(long sampleId, String kind, String name, String aliases, String summary,
                    String contentMd, String relations, int importance, Integer firstSeq, int mentions);

    /** 软删该样本全部卡（重合成前清理，人工编辑走单卡 PUT 不受影响直至重新解析）。 */
    int softDeleteBySample(long sampleId);

    /** 单卡软删（人工纠偏删除）。 */
    void softDeleteById(long cardId);
}
