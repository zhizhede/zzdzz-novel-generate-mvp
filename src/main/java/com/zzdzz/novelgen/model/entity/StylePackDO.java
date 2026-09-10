package com.zzdzz.novelgen.model.entity;

public record StylePackDO(Long id, String name, String description, String rulesMd,
        String fingerprint, boolean isDeleted) {}
