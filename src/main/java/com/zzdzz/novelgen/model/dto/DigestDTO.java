package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** DigestDTO。 */
@Data
@TableName(value = "digests")
public class DigestDTO extends BaseDTO {
    private long chapterId;
    private String contentMd;
    private String facts;
}
