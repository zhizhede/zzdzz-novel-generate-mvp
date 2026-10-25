package com.zzdzz.novelgen.derive.contract;

import java.util.List;

/** 稳定知识事实；模型通过 factKey 引用，不自由发明事实文本。 */
public record KnowledgeFact(
        String factKey,
        String canonicalText,
        List<String> writerKeys,
        List<String> readerKeys) {
}