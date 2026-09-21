package com.zzdzz.novelgen.model.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** DigestDTO。 */
@Data
@TableName(value = "digests")
public class DigestDTO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private long chapterId;
    private String contentMd;
    private String facts;
    private boolean isDeleted;




}
