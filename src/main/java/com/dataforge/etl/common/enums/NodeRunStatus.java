package com.dataforge.etl.common.enums;

/** 节点运行状态:与 RunStatus 一致,另加 SKIPPED(因上游失败被跳过) */
public enum NodeRunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
    PARTIAL_SUCCESS,
    SKIPPED
}
