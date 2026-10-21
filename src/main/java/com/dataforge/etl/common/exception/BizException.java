package com.dataforge.etl.common.exception;

/**
 * 业务异常:code 作为统一响应中的错误码,同时映射为 HTTP 状态
 * (404 → 404,其余 → 400)。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 参数/状态类错误,HTTP 400 */
    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    /** 资源不存在,HTTP 404 */
    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public int getCode() {
        return code;
    }

    /** 映射到 HTTP 状态码,保持 200/400/404 语义 */
    public int getHttpStatus() {
        return code == 404 ? 404 : 400;
    }
}
