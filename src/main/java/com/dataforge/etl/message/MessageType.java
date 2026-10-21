package com.dataforge.etl.message;

/**
 * Agent 间消息类型。
 *
 * <p>DATA_HANDOFF:有向数据交接(上游 Agent → 下游 Agent,携带行数),
 * 由执行引擎在节点拿到上游产出时发出,对应 DAG 上的一条边;
 * DATA_READY:完成广播(Agent → 全体),节点成功产出后发出。</p>
 */
public enum MessageType {
    /** 数据交接:上游 Agent 把产出交给下游 Agent(有向、带行数) */
    DATA_HANDOFF,
    /** 产出就绪广播:Agent 完成节点后向全员广播 */
    DATA_READY
}
