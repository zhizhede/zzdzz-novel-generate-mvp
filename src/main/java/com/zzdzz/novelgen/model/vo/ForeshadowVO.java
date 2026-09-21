package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.dto.ForeshadowDTO;

/** 伏笔台账行：软删除内部字段不出 API。 */
public record ForeshadowVO(Long id, long novelId, String code, String content,
                           Integer plantedIn, Integer recoveredIn, Integer proposedIn, String status) {

    public static ForeshadowVO from(ForeshadowDTO d) {
        return new ForeshadowVO(d.getId(), d.getNovelId(), d.getCode(), d.getContent(),
                d.getPlantedIn(), d.getRecoveredIn(), d.getProposedIn(), d.getStatus());
    }
}
