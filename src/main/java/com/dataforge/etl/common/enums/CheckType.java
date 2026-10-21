package com.dataforge.etl.common.enums;

/** 数据质量检查类型 */
public enum CheckType {
    NULL_CHECK,
    RANGE_CHECK,
    UNIQUE_CHECK,
    REGEX_CHECK,
    ENUM_CHECK,
    ROW_COUNT
}
