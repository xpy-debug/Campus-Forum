package com.school.forum.common.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应体。所有 Controller 的返回值都必须是本类型或其包装。
 *
 * <pre>
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": { },
 *   "traceId": "a1b2c3d4e5f6"
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务错误码，0 表示成功。与 HTTP 状态码解耦：HTTP 层统一返回 200，业务结果看本字段 */
    private int code;

    /** 提示信息，可直接展示给用户 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 链路追踪 ID，便于用户报障时快速定位日志 */
    private String traceId;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ==================== 成功 ====================

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 返回成功但自定义提示信息。用于「执行成功但需要告诉用户一个补充说明」的场景，
     * 例如重复点赞时返回「已点赞」而非报错。
     */
    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ==================== 辅助方法 ====================

    /**
     * 是否成功。标注 {@link JsonIgnore} 是为了不让它出现在序列化结果里——
     * 前端只需要判断 code 字段，多一个冗余字段只会造成歧义。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }

    /** 链式填充 traceId，避免在每个 Controller 里手动 set */
    public Result<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }
}
