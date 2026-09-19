package com.school.forum.infrastructure.mq.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.exception.BizException;
import com.school.forum.infrastructure.mq.metrics.MqMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消费侧的统一调度器。Kafka 与 RabbitMQ 两套实现都调用本类，
 * 从而保证「幂等 + 重试判定 + 埋点」这段逻辑在两个产品下<b>完全一致</b>。
 *
 * <p><b>为什么必须共享：</b>如果 Kafka 的监听器里写一套重试逻辑、
 * RabbitMQ 的监听器里再写一套，那么压测时两者的差异就分不清是 MQ 本身的
 * 差异还是代码实现差异。共享本类之后，两种实现下唯一不同的是
 * 「消息怎么从 Broker 到本方法」这一段，对比结论才有意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDispatcher {

    private final ObjectMapper objectMapper;
    private final IdempotentGuard idempotentGuard;
    private final MqMetricsRecorder metricsRecorder;
    private final MqProperties properties;

    /**
     * 处理一条原始消息。
     *
     * @param handler    业务处理器
     * @param payload    消息体（JSON 字符串）
     * @param retryCount 本条消息已被重试的次数，由各 MQ 实现从消息头读取
     * @return 处理结论，由调用方翻译成对应产品的 ack / nack 动作
     */
    public ConsumeResult dispatch(EventHandler<?> handler, String payload, int retryCount) {
        long startNanos = System.nanoTime();
        String topic = handler.topic();
        String group = handler.consumerGroup();

        BaseEvent event = null;
        ConsumeResult result = ConsumeResult.SUCCESS;
        String errorMsg = "";

        try {
            event = deserialize(handler, payload);

            // ---------- 幂等 ----------
            if (!idempotentGuard.tryAcquire(group, event.getEventId())) {
                log.info("重复消息，已忽略。topic={}, group={}, eventId={}", topic, group, event.getEventId());
                // 按成功处理：这条消息的业务效果已经产生过了，重投只会浪费资源
                return ConsumeResult.SUCCESS;
            }

            // ---------- 执行业务 ----------
            try {
                result = invoke(handler, event);
            } catch (Exception e) {
                // 业务异常 → 规则性拒绝，重试多少次都是一样的结果，直接丢弃进死信
                // 系统异常 → 可能是下游抖动，值得重试
                if (e instanceof BizException biz) {
                    log.warn("业务规则拒绝消息。topic={}, eventId={}, code={}, msg={}",
                            topic, event.getEventId(), biz.getCode(), biz.getMessage());
                    result = ConsumeResult.DISCARD;
                    errorMsg = "BizException[" + biz.getCode() + "]: " + biz.getMessage();
                } else {
                    log.error("消息处理异常，将重试。topic={}, eventId={}", topic, event.getEventId(), e);
                    result = ConsumeResult.RETRY;
                    errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }

            // 重试次数超限则降级为丢弃，防止毒消息无限循环堵死消费线程
            result = ConsumeResult.resolve(result, retryCount);

            // ---------- 幂等标记的释放 ----------
            // 失败必须释放，否则重投时会被自己的幂等标记挡住，消息就此丢失
            if (result != ConsumeResult.SUCCESS) {
                idempotentGuard.release(group, event.getEventId());
            }

            if (result == ConsumeResult.DISCARD) {
                log.error("消息进入死信（不再重投）。topic={}, group={}, eventId={}, retry={}, 原因={}",
                        topic, group, event.getEventId(), retryCount, errorMsg);
            }
            return result;

        } catch (Exception e) {
            // 走到这里说明是反序列化失败或幂等组件本身抛错——
            // 这类问题重试不会自愈，直接丢弃，避免坏消息反复占用消费线程
            log.error("消息无法解析，已丢弃。topic={}, group={}, payload={}", topic, group, truncate(payload), e);
            result = ConsumeResult.DISCARD;
            errorMsg = "反序列化失败: " + e.getMessage();
            return result;

        } finally {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            metricsRecorder.record(
                    event == null ? null : event.getEventId(),
                    group,
                    properties.getProvider(),
                    topic,
                    event == null ? topic : event.getClass().getSimpleName(),
                    result,
                    retryCount,
                    (int) costMs,
                    errorMsg);
        }
    }

    /**
     * 反序列化。泛型擦除导致这里必须做一次不受检的强转——
     * 由 {@link EventHandler#eventType()} 在运行期提供真实类型，所以是安全的。
     */
    @SuppressWarnings("unchecked")
    private BaseEvent deserialize(EventHandler<?> handler, String payload) throws Exception {
        EventHandler<BaseEvent> typed = (EventHandler<BaseEvent>) handler;
        return objectMapper.readValue(payload, typed.eventType());
    }

    @SuppressWarnings("unchecked")
    private ConsumeResult invoke(EventHandler<?> handler, BaseEvent event) {
        EventHandler<BaseEvent> typed = (EventHandler<BaseEvent>) handler;
        return typed.handle(event);
    }

    private String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...(已截断)";
    }
}
