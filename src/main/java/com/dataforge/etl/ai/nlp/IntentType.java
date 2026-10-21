package com.dataforge.etl.ai.nlp;

/**
 * 意图类型:AI 助手支持的全部意图枚举。
 */
public enum IntentType {
    /** 查询平台总览 */
    QUERY_OVERVIEW,
    /** 查询失败运行 */
    QUERY_FAILED_RUNS,
    /** 查询某次运行详情 */
    QUERY_RUN_DETAIL,
    /** 查询 Agent 状态 */
    QUERY_AGENTS,
    /** 查询数据质量 */
    QUERY_QUALITY,
    /** 触发管道(危险操作) */
    TRIGGER_PIPELINE,
    /** 诊断某次运行 */
    DIAGNOSE_RUN,
    /** 生成新管道 */
    GENERATE_PIPELINE,
    /** 平台知识问答(走 RAG) */
    KNOWLEDGE_QA,
    /** 帮助 */
    HELP,
    /** 未识别 */
    UNKNOWN
}
