package com.dataforge.etl.common.enums;

/** 管道失败策略:快速失败(中止后续节点) / 继续(跳过下游,其余分支继续) */
public enum FailureStrategy {
    FAIL_FAST,
    CONTINUE
}
