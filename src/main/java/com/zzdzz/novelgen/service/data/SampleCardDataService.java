package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.SampleCardDO;

import java.util.List;

/** 导入样本结构化资产卡数据服务。 */
public interface SampleCardDataService extends IService<SampleCardDO> {

    /** 该样本全部活跃卡（按 importance 降序、id 升序）。 */
    List<SampleCardDO> listBySample(long sampleId);

    /** 落卡（aliases/relations 为 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insertCard(long sampleId, String kind, String name, String aliases, String summary,
                    String contentMd, String relations, int importance, Integer firstSeq, int mentions);

    /** 物理删除该样本全部卡（重合成前清理，人工编辑走单卡 PUT 不受影响直至重新解析）。 */
    int deleteBySample(long sampleId);

    /** 单卡删除（人工纠偏）。 */
    void deleteCard(long cardId);
}
