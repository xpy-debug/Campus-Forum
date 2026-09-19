package com.school.forum.common.exception;

import com.school.forum.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>约定：所有可预期的业务失败都抛本异常，由 {@code GlobalExceptionHandler} 统一转换为
 * {@link com.school.forum.common.result.Result}。
 *
 * <p><b>不要用本异常表达系统故障</b>——数据库连接失败、远程调用超时等应抛
 * {@code SystemException} 或让原始异常冒泡，因为二者的告警级别与处理方式完全不同：
 * 业务异常只记 INFO 日志，系统异常必须记 ERROR 并触发告警。
 *
 * <p>本异常默认不填充堆栈（{@code super(..., false)}）。高频业务异常（如秒杀已抢光）
 * 每秒可能抛出数千次，构造堆栈的成本会显著影响吞吐。排查问题时靠错误码与
 * traceId 定位即可，不需要调用栈。
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage(), null, false, false);
        this.errorCode = errorCode;
    }

    /** 自定义提示信息，覆盖 ErrorCode 中的默认文案 */
    public BizException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    /** 需要保留堆栈的场景（如包装下游异常） */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause, false, true);
        this.errorCode = errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    // ==================== 便捷断言方法 ====================

    /** 条件为真时抛出异常 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    /** 对象为 null 时抛出异常 */
    public static <T> T requireNonNull(T obj, ErrorCode errorCode) {
        if (obj == null) {
            throw new BizException(errorCode);
        }
        return obj;
    }
}
