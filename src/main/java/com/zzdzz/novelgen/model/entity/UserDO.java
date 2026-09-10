package com.zzdzz.novelgen.model.entity;

public record UserDO(Long id, String username, String passwordHash, String role,
        boolean isDeleted) {}
