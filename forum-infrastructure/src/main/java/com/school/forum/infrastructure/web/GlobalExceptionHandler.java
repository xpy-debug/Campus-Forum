package com.school.forum.infrastructure.web;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import com.school.forum.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p><b>核心约定：HTTP 状态码一律 200，业务结果由响应体的 {@code code} 字段表达。</b>
 * 这样前端只需要处理一套逻辑（解析 body → 判断 code），
 * 不用为 4xx/5xx 再写一套拦截器。网关和监控也不容易把「业务拒绝」
 * （如「已抢光」）误判成「系统故障」而触发无效告警。
 *
 * <p><b>日志级别是有讲究的，不是随手写的：</b>
 * <ul>
 *   <li>业务异常 → {@code WARN}，且不打堆栈。秒杀「已抢光」每秒可能几千次，
 *       打堆栈会瞬间刷满磁盘并把 CPU 吃在异常构造上。</li>
 *   <li>参数校验失败 → {@code WARN}，不打堆栈。属于客户端问题，但要能统计到
 *       是哪个接口在被错误调用。</li>
 *   <li>未捕获异常 → {@code ERROR} + 完整堆栈。这是唯一需要告警的类别。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 链路追踪 ID，由 TraceIdFilter 写入 MDC */
    private static final String TRACE_ID = "traceId";

    // ==================== 业务异常 ====================

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常。uri={}, code={}, message={}",
                request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.<Void>fail(e.getCode(), e.getMessage()).withTraceId(currentTraceId());
    }

    // ==================== 参数校验 ====================

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                     HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败。uri={}, message={}", request.getRequestURI(), message);
        return Result.<Void>fail(ErrorCode.PARAM_INVALID, message).withTraceId(currentTraceId());
    }

    /** 表单/查询参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败。uri={}, message={}", request.getRequestURI(), message);
        return Result.<Void>fail(ErrorCode.PARAM_INVALID, message).withTraceId(currentTraceId());
    }

    /** 方法参数上的 {@code @Validated} 校验失败（如 {@code @PathVariable @Min(1)}） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e,
                                                  HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("约束校验失败。uri={}, message={}", request.getRequestURI(), message);
        return Result.<Void>fail(ErrorCode.PARAM_INVALID, message).withTraceId(currentTraceId());
    }

    /** 缺少必填的请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e,
                                           HttpServletRequest request) {
        log.warn("缺少请求参数。uri={}, param={}", request.getRequestURI(), e.getParameterName());
        return Result.<Void>fail(ErrorCode.PARAM_INVALID,
                "缺少必填参数：" + e.getParameterName()).withTraceId(currentTraceId());
    }

    /** 参数类型不匹配，如 {@code /post/abc} 传了个非数字的 id */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                           HttpServletRequest request) {
        log.warn("参数类型不匹配。uri={}, param={}", request.getRequestURI(), e.getName());
        return Result.<Void>fail(ErrorCode.PARAM_INVALID,
                "参数格式不正确：" + e.getName()).withTraceId(currentTraceId());
    }

    /** 请求体不是合法 JSON。多数情况是前端漏了 Content-Type，这里点明，省去排查时间 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e,
                                          HttpServletRequest request) {
        log.warn("请求体解析失败。uri={}", request.getRequestURI());
        return Result.<Void>fail(ErrorCode.PARAM_INVALID,
                "请求体格式不正确，请确认 Content-Type 为 application/json")
                .withTraceId(currentTraceId());
    }

    // ==================== 路由与方法 ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                  HttpServletRequest request) {
        log.warn("请求方法不支持。uri={}, method={}", request.getRequestURI(), e.getMethod());
        return Result.<Void>fail(ErrorCode.METHOD_NOT_ALLOWED).withTraceId(currentTraceId());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e, HttpServletRequest request) {
        log.warn("接口不存在。uri={}", request.getRequestURI());
        return Result.<Void>fail(ErrorCode.NOT_FOUND).withTraceId(currentTraceId());
    }

    /**
     * 找不到静态资源——**实际兜住「接口路径写错」的就是这一条，不是上面那条**。
     *
     * <p>Spring Boot 3.2（Spring Framework 6.1）起，缺省注册的
     * {@code ResourceHttpRequestHandler} 会接管 {@code /**}：请求打到不存在的路径时，
     * DispatcherServlet 是**找得到 handler 的**（就是这个资源处理器），
     * 只是它找不到对应资源，于是抛 {@link NoResourceFoundException}。
     * 这与 {@link NoHandlerFoundException} 是两个不同的类，
     * 上面那个 {@code @ExceptionHandler} 因此**永远不会被触发**。
     *
     * <p>漏掉这一条的后果不是 404 变成 500，而是**404 变成 50000「服务器内部错误」**：
     * 异常一路掉进最下面的 {@code Exception} 兜底，被当成系统故障记了 error 日志。
     * 排查时它和「接口真的炸了」在响应体上一模一样，只能靠翻日志区分——
     * 本次就是因为这个，把「8080 上那个旧实例没有 points 路由」误读成了「旧代码在报错」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("接口或资源不存在。uri={}", request.getRequestURI());
        return Result.<Void>fail(ErrorCode.NOT_FOUND).withTraceId(currentTraceId());
    }

    // ==================== 兜底 ====================

    /**
     * 未预期的异常。
     *
     * <p><b>这里绝不把原始异常信息返回给前端。</b>
     * 异常消息里可能含有 SQL 片段、表名、内网地址、甚至连接串，
     * 泄露出去等于免费给攻击者做了一次信息收集。对外只给一个通用提示 + traceId，
     * 完整的堆栈只进服务端日志，靠 traceId 关联。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常。uri={}, traceId={}", request.getRequestURI(), currentTraceId(), e);
        return Result.<Void>fail(ErrorCode.SYSTEM_ERROR).withTraceId(currentTraceId());
    }

    private String currentTraceId() {
        return org.slf4j.MDC.get(TRACE_ID);
    }
}
