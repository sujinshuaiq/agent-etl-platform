package com.dataforge.etl.engine;

/**
 * 节点执行输出:输出行数、过滤行数与说明信息。
 */
public record NodeOutput(long rowsOut, long rowsFiltered, String message) {

    public static NodeOutput of(long rowsOut, String message) {
        return new NodeOutput(rowsOut, 0, message);
    }
}
