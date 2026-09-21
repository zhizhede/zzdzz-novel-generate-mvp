package com.zzdzz.novelgen.model.vo;

import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import java.util.List;

/** 素材卡：软删除内部字段不出 API。 */
public record MaterialCardVO(Long id, long novelId, String kind, String name, List<String> aliases,
                             String summary, String contentMd, boolean pinned, String status,
                             Integer sourceChapter) {

    public static MaterialCardVO from(MaterialCardDTO d) {
        return new MaterialCardVO(d.getId(), d.getNovelId(), d.getKind(), d.getName(), d.getAliases(),
                d.getSummary(), d.getContentMd(), d.isPinned(), d.getStatus(), d.getSourceChapter());
    }
}
