package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;

import java.util.List;

/** 导入样本剧情结构树数据服务。 */
public interface SamplePlotNodeDataService extends IService<SamplePlotNodeDO> {

    /** 该样本全部活跃节点（按 level+seq 升序）。 */
    List<SamplePlotNodeDO> listBySample(long sampleId);

    /** 跨书聚合（SPLICE 素材池①摘取）：多本样本的**章级**剧情节点，按样本、章序排列；
     * 取样单元寻址 = (sampleId, seq)，层标记由 level='chapter' 隐含（大框架 book/volume 层另取）。 */
    List<SamplePlotNodeDO> listChapterNodesBySamples(java.util.List<Long> sampleIds);

    /** 层内序号定位（断点 checkpoint 判据），无则 null。 */
    SamplePlotNodeDO findBySeq(long sampleId, String level, int seq);

    /** 落节点（beats/meta 为 JSON 文本，XML 内 ::jsonb 转型）。 */
    long insertNode(long sampleId, String level, int seq, int parentSeq, String title,
                    String summary, String beats, String meta);

    /** 物理删除某层全部节点（重合成前清理）。 */
    int deleteByLevel(long sampleId, String level);
}
