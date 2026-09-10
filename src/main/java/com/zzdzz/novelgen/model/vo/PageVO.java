package com.zzdzz.novelgen.model.vo;

/** 分页包装。 */
public record PageVO<T>(java.util.List<T> items, long total, int page, int size) {
}
