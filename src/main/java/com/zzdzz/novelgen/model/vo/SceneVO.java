package com.zzdzz.novelgen.model.vo;

/** 场景详情项（含草稿与门禁状态）。 */
public record SceneVO(Long id, int sceneNo, String goal, String draftText,
                      String gateStatus, int revisionRound) {
}
