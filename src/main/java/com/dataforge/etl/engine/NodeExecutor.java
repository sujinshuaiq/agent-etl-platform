package com.dataforge.etl.engine;

import com.dataforge.etl.common.enums.AgentType;

/**
 * 节点执行器策略接口:每种 AgentType 对应一个实现(策略模式)。
 * 扩展新节点类型时,新增实现并注册为 Spring Bean 即可被引擎自动发现。
 */
public interface NodeExecutor {

    /** 本执行器支持的 Agent 类型 */
    AgentType supportType();

    /** 执行节点逻辑,返回行数输出;失败抛出异常(引擎负责重试) */
    NodeOutput execute(NodeExecutionContext ctx) throws Exception;
}
