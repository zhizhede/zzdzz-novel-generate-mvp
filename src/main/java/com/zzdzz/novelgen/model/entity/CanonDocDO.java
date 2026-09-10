package com.zzdzz.novelgen.model.entity;

public record CanonDocDO(Long id, long novelId, String kind, String name, String content,
        int sortNo, boolean isDeleted) {}
