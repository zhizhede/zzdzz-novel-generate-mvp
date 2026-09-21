package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.dto.RetroProposalDTO;
import java.time.OffsetDateTime;

/** 复盘提案：软删除三件套不出 API，createTime 留展示。 */
public record RetroProposalVO(Long id, Long novelId, Integer volNo, String kind, String content,
                              String status, String decisionNote, OffsetDateTime createTime) {

    public static RetroProposalVO from(RetroProposalDTO d) {
        return new RetroProposalVO(d.getId(), d.getNovelId(), d.getVolNo(), d.getKind(), d.getContent(),
                d.getStatus(), d.getDecisionNote(), d.getCreateTime());
    }
}
