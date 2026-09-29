package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;

import java.util.List;

/** 导入样本剧情结构树数据服务。 */
public interface SamplePlotNodeDataService extends IService<SamplePlotNodeDTO> {

    /** 该样本全部活跃节点（按 level+seq 升序）。 */
    List<SamplePlotNodeDTO> listBySample(long sampleId);

    /** 层内序号定位（断点 checkpoint 判据），无则 null。 */
    SamplePlotNodeDTO findBySeq(long sampleId, String level, int seq);

    /** 落节点（beats/meta 为 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insertNode(long sampleId, String level, int seq, int parentSeq, String title,
                    String summary, String beats, String meta);

    /** 软删某层全部节点（重合成前清理）。 */
    int softDeleteByLevel(long sampleId, String level);
}
