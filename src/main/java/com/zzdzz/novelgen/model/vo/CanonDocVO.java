package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.entity.CanonDocDO;

/** 正典文档：软删除内部字段不出 API。 */
public record CanonDocVO(Long id, long novelId, String kind, String name, String content, int sortNo) {

    public static CanonDocVO from(CanonDocDO d) {
        return new CanonDocVO(d.getId(), d.getNovelId(), d.getKind(), d.getName(), d.getContent(), d.getSortNo());
    }
}
