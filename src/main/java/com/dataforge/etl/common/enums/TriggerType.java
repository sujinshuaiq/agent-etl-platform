package com.dataforge.etl.common.enums;

/** 运行触发方式:手动 / 调度 / API / 重跑 */
public enum TriggerType {
    MANUAL,
    SCHEDULED,
    API,
    RETRY
}
