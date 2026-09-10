package com.zzdzz.novelgen.model.entity;

public record DigestDO(Long id, long chapterId, String contentMd, String facts,
        boolean isDeleted) {}
