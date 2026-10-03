package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** DigestDO。 */
@Data
@TableName(value = "digests")
public class DigestDO extends BaseDO {
    private long chapterId;
    private String contentMd;
    private String facts;
}
