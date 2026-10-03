package com.zzdzz.novelgen.model.dto;

/** 登录入参。按规约属「入口层收 DTO」（model/dto），不再是 VO——它不返回给前端。 */
public record LoginDTO(String username, String password) {
}
