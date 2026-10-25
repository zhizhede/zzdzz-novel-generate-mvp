package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.contract.KnowledgeFact;
import com.zzdzz.novelgen.derive.contract.ScaffoldBeat;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * P2 Scaffold：章号、卷范围、合法拍编号由系统生成。
 * 模型只能引用这些稳定 ID，不能自行发明章号或拍号。
 */
@Service
public class DeriveScaffoldService {

    static final int DEFAULT_CHAPTER_AVG = 2500;

    public ScaffoldContract buildScaffold(RunDO run, int beatCount, int volumeCount) {
        return buildScaffold(run, beatCount, volumeCount, beatCount);
    }

    public ScaffoldContract buildScaffold(RunDO run, int beatCount, int volumeCount,
                                          int chapterCount) {
        if (run == null || beatCount < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "scaffold 需要 run 和至少 1 个拍");
        }
        if (volumeCount < 1) {
            volumeCount = 1;
        }
        // 章数由规模合同决定；拍是结构真源，允许多拍合并到一章，缺拍由后续 synthetic 补齐。
        if (chapterCount < 1) {
            chapterCount = beatCount;
        }
        List<ScaffoldContract.VolumeRange> ranges = volumeRanges(chapterCount, volumeCount);
        List<ScaffoldBeat> beats = new ArrayList<>();
        for (int seq = 1; seq <= beatCount; seq++) {
            int chapterNo = Math.min(chapterCount,
                    (int) Math.ceil(seq * (double) chapterCount / beatCount));
            beats.add(new ScaffoldBeat(seq, chapterNo, "事件", "中段",
                    "系统骨架拍-" + seq, DeriveFactService.factKey("骨架事实-" + seq)));
        }
        List<KnowledgeFact> facts = new ArrayList<>();
        for (ScaffoldBeat beat : beats) {
            facts.add(new KnowledgeFact(beat.factKey(), beat.sourceLabel(),
                    List.of("C1"), List.of()));
        }
        return new ScaffoldContract(chapterCount, volumeCount, ranges, beats, facts);
    }

    static List<ScaffoldContract.VolumeRange> volumeRanges(int chapterCount, int volumeCount) {
        List<ScaffoldContract.VolumeRange> out = new ArrayList<>();
        for (int volumeNo = 1; volumeNo <= volumeCount; volumeNo++) {
            int from = (volumeNo - 1) * chapterCount / volumeCount + 1;
            int to = volumeNo == volumeCount
                    ? chapterCount : volumeNo * chapterCount / volumeCount;
            out.add(new ScaffoldContract.VolumeRange(volumeNo, from, to));
        }
        return out;
    }
}