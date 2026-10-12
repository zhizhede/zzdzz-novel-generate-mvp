package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** wordlist · 词表（v1.2 §3.9，④/⑧；v1 散落脚本的词表全部入库，脚本改读表）。全标量列。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wordlist")
public class WordlistDO extends DeriveBaseDO {

    /** 词表种类：BLACKLIST=来源/作品名 / DOMAIN_WORD=源领域词 / CATEGORY_TERM=类别浓度词 / ANTI_DOMAIN=反域词·组级 */
    private String kind;

    /** 词 */
    private String word;

    /** 归属组（ANTI_DOMAIN 按组挂载；同词可合法归属不同组，故不设唯一约束） */
    private String grp;

    /** 类别（CATEGORY_TERM 时填） */
    private String category;
}
