package com.school.forum.notification.event;

import com.school.forum.infrastructure.mq.core.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 链路自检事件。
 *
 * <p>{@code sentAtMillis} 用毫秒时间戳而不是 {@code LocalDateTime}：
 * 端到端延迟要算的是「生产者发出」到「消费者收到」的差值，
 * 用毫秒时间戳可以直接相减，不需要处理时区和格式化。
 * 父类里的 {@code occurredAt} 是给人看的，这个字段是给程序算的，两者用途不同，都保留。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SelfTestEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 调用方附带的说明文字，用于区分是人工触发还是压测脚本触发 */
    private String note;

    /** 生产者发出时刻（毫秒时间戳），用于计算端到端延迟 */
    private long sentAtMillis;

    public SelfTestEvent(String note) {
        this.note = note;
        this.sentAtMillis = System.currentTimeMillis();
    }

    /**
     * 自检消息不关心顺序，固定返回同一个键即可。
     *
     * <p>这里刻意让它落到固定分区（Kafka）或固定队列（RabbitMQ）：
     * 自检本来就是低频操作，固定键能让消息在 Kafka UI / RabbitMQ 控制台里
     * 更容易被找到，也避免自检流量被分散到所有分区而干扰压测观察。
     */
    @Override
    public String routingKey() {
        return "selftest";
    }
}
