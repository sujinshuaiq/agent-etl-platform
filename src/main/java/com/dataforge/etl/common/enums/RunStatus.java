package com.dataforge.etl.common.enums;

/** 管道运行状态 */
public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
    PARTIAL_SUCCESS
}
