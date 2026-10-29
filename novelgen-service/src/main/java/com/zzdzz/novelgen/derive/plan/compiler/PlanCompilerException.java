package com.zzdzz.novelgen.derive.plan.compiler;

/** 只有无法构造章节骨架时才抛出的计划编译失败。 */
public class PlanCompilerException extends RuntimeException {

    public PlanCompilerException(String message) {
        super(message);
    }

    public PlanCompilerException(String message, Throwable cause) {
        super(message, cause);
    }
}
