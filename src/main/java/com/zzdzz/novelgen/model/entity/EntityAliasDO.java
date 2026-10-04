package com.zzdzz.novelgen.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 可反查的别名索引（V40）：一行 = 本书的一个名字 → 它所属的素材卡。派生自 material_cards，不是第二个真源。 */
@Data
@TableName("entity_aliases")
public class EntityAliasDO extends BaseDO {
    private Long novelId;
    private Long cardId;
    private String alias;
    /** 冗余的卡名：反查的调用方要的就是它（卡改名时整书重建索引）。 */
    private String cardName;
    private String cardKind;
    /** true＝这一行是卡名本身，false＝卡的别名。 */
    private Boolean isPrimary;
}
