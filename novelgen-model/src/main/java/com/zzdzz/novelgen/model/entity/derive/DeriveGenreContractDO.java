package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 类型合同：由 GenreContractCodec 编码/解码，保存在 derive_genre_contract。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derive_genre_contract")
public class DeriveGenreContractDO extends DeriveBaseDO {

    private Long runId;
    private String genre;
    private Integer contractVersion;
    private String scope;
    private String payload;
    private String status;
}
