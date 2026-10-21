package com.dataforge.etl.common.enums;

/** Agent 类型:抽取 / 转换 / 加载 / 质量检查 / 编排 */
public enum AgentType {
    EXTRACTOR,
    TRANSFORMER,
    LOADER,
    QUALITY_CHECKER,
    ORCHESTRATOR
}
