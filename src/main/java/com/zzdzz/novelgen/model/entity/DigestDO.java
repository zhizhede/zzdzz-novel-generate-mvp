package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** DigestDO。 */
@Data
@TableName(value = "digests")
public class DigestDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long chapterId;
    private String contentMd;
    private String facts;
    private boolean isDeleted;

    @Deprecated
    public Long id() {
        return getId();
    }

    @Deprecated
    public long chapterId() {
        return getChapterId();
    }

    @Deprecated
    public String contentMd() {
        return getContentMd();
    }

    @Deprecated
    public String facts() {
        return getFacts();
    }
}
