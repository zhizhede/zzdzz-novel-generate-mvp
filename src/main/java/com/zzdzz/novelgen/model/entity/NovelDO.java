package com.zzdzz.novelgen.model.entity;

public record NovelDO(Long id, long userId, String title, String description,
        Long stylePackId, String approvalMode, String status, boolean isDeleted) {}
