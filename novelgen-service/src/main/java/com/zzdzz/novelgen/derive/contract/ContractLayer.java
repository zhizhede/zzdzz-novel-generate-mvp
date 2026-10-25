package com.zzdzz.novelgen.derive.contract;

/** 合同分层：结构事实由系统生成，创作内容由模型填充，状态与评审独立校验。 */
public enum ContractLayer {
    USER,
    SCAFFOLD,
    CREATIVE,
    STATE,
    REVIEW
}