package com.school.forum.infrastructure.mq.core;

/**
 * 消费者的处理结论。抽象层据此决定「这条消息接下来怎么办」，
 * 从而把两个 MQ 语义不同的 ack / nack / requeue 行为统一成一套。
 *
 * <table border="1">
 *   <caption>两种产品的语义映射</caption>
 *   <tr><th>ConsumeResult</th><th>Kafka</th><th>RabbitMQ</th></tr>
 *   <tr>
 *     <td>{@link #SUCCESS}</td>
 *     <td>提交 offset</td>
 *     <td>basicAck</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #RETRY}</td>
 *     <td>不提交 offset，seek 回原位置（或投递到重试 topic）</td>
 *     <td>basicNack(requeue=true)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #DISCARD}</td>
 *     <td>提交 offset（丢弃）</td>
 *     <td>basicNack(requeue=false) → 进死信队列</td>
 *   </tr>
 * </table>
 *
 * <p><b>重试次数的上限由抽象层统一控制</b>，不交给业务方判断。原因：RabbitMQ 的
 * {@code requeue=true} 若消费者持续失败会造成「毒消息」无限循环，把消费者线程
 * 彻底堵死；Kafka 的 seek 回退同样会卡住分区。抽象层在消息头里维护
 * {@code x-retry-count}，超过 {@link #MAX_RETRY} 次一律转 DISCARD 语义，
 * 保证坏消息只会拖慢而不会拖死整个消费链路。
 */
public enum ConsumeResult {

    /** 处理成功，消息可以从队列中移除 */
    SUCCESS,

    /** 处理失败但可重试（如下游数据库瞬时不可用）。由抽象层记录重试次数 */
    RETRY,

    /**
     * 不可重试的失败（如参数非法、业务规则拒绝），或重试次数已超限。
     * 消息不再重投，转入死信队列等待人工处理。
     */
    DISCARD;

    /** 最大重试次数。超过后即使返回 RETRY 也会按 DISCARD 处理 */
    public static final int MAX_RETRY = 3;

    /**
     * 把重试次数换算成最终结论。
     *
     * @param result     业务方返回的原始结论
     * @param retryCount 本条消息已经重试过的次数（来自消息头）
     */
    public static ConsumeResult resolve(ConsumeResult result, int retryCount) {
        if (result == RETRY && retryCount >= MAX_RETRY) {
            return DISCARD;
        }
        return result;
    }
}
