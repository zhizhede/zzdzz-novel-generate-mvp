package com.zzdzz.novelgen.model.entity;

public record ForeshadowDO(Long id, long novelId, String code, String content,
        Integer plantedIn, Integer recoveredIn, String status, boolean isDeleted) {}
