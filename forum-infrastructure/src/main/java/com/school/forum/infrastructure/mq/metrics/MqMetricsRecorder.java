package com.school.forum.infrastructure.mq.metrics;

import com.school.forum.infrastructure.mq.core.ConsumeResult;
import com.school.forum.infrastructure.mq.core.MqProperties;
import com.school.forum.infrastructure.mq.mapper.MqConsumeLogMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费埋点收集器。
 *
 * <p><b>设计要点：内存聚合 + 定时批量落库，绝不同步写库。</b>
 * 如果每条消息同步写一行 {@code t_mq_consume_log}，MySQL 会立刻成为瓶颈
 * （单机约几千 TPS），此时测出来的是数据库的吞吐，不是 MQ 的吞吐，
 * Kafka 和 RabbitMQ 都会撞到同一堵墙，对比彻底失效。
 *
 * <p>做极限吞吐压测时把 {@code forum.mq.metrics.enabled} 设为 {@code false}，
 * 埋点开销降到接近零，得到的才是 MQ 的真实上限。
 *
 * <p><b>写库方式：</b>定时把缓冲区整批交给
 * {@link MqConsumeLogMapper#upsertBatch}，用 {@code ON DUPLICATE KEY UPDATE}
 * 合并重试记录（原因见该方法注释）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqMetricsRecorder {

    private final MqConsumeLogMapper mapper;
    private final MqProperties properties;

    /** 缓冲区。用无界队列实现有界语义：超出上限时丢最旧的，避免压测把内存撑爆 */
    private final ConcurrentLinkedQueue<MqConsumeLog> buffer = new ConcurrentLinkedQueue<>();

    private final AtomicInteger bufferSize = new AtomicInteger();

    /** 因缓冲区满而被丢弃的记录数，用于判断埋点数据是否可信 */
    private final AtomicLong droppedCount = new AtomicLong();

    /** 落库失败的次数。持续增长说明埋点表写入有问题 */
    private final AtomicLong flushFailCount = new AtomicLong();

    /**
     * 记录一次消费。
     *
     * <p><b>本方法必须极快且不抛异常</b>——它在消费线程的 finally 块里被调用，
     * 一旦阻塞或抛错会直接影响消费吞吐，甚至把异常盖住业务异常。
     */
    public void record(String messageId, String consumerGroup, String provider,
                       String topic, String bizType, ConsumeResult result,
                       int retryCount, int costMs, String errorMsg) {
        if (!properties.getMetrics().isEnabled()) {
            return;
        }
        try {
            MqConsumeLog entity = new MqConsumeLog();
            // eventId 为空时退化为一个占位值，避免 NOT NULL 约束导致整批插入失败。
            // 这种情况本身已经记过 WARN 了。
            entity.setMessageId(messageId == null || messageId.isBlank()
                    ? "unknown-" + java.util.UUID.randomUUID().toString().replace("-", "")
                    : messageId);
            entity.setConsumerGroup(consumerGroup);
            entity.setProvider(provider);
            entity.setTopic(topic);
            entity.setBizType(bizType);
            entity.setStatus(result == ConsumeResult.SUCCESS
                    ? MqConsumeLog.STATUS_SUCCESS
                    : MqConsumeLog.STATUS_FAILED);
            entity.setRetryCount(retryCount);
            entity.setCostMs(costMs);
            entity.setErrorMsg(errorMsg == null ? "" : truncate(errorMsg));
            entity.setCreateTime(LocalDateTime.now());

            buffer.offer(entity);
            if (bufferSize.incrementAndGet() > properties.getMetrics().getBufferSize()) {
                // 超限：丢最旧的一条，保证内存可控
                if (buffer.poll() != null) {
                    bufferSize.decrementAndGet();
                    droppedCount.incrementAndGet();
                }
            }
        } catch (Exception e) {
            // 埋点失败绝不能影响业务消费
            log.warn("消费埋点记录失败", e);
        }
    }

    /**
     * 定时落库。
     * <p>{@code fixedDelay} 而非 {@code fixedRate}：上一次还没跑完时不该叠加新任务，
     * 否则落库变慢时会累积出一堆并发任务，反而把数据库压垮。
     */
    @Scheduled(fixedDelayString = "${forum.mq.metrics.flush-interval-ms:5000}")
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<MqConsumeLog> batch = drain();
        if (batch.isEmpty()) {
            return;
        }
        // SQL 执行日志在压测时会刷屏，这里只保留异常日志
        try {
            mapper.upsertBatch(batch);
            log.debug("消费埋点落库 {} 条", batch.size());
        } catch (Exception e) {
            flushFailCount.incrementAndGet();
            log.error("消费埋点落库失败，本批 {} 条已丢弃。累计失败 {} 次",
                    batch.size(), flushFailCount.get(), e);
        }
    }

    /** 关闭前把缓冲区刷干净，否则最后几秒的埋点会丢 */
    @PreDestroy
    public void flushOnShutdown() {
        log.info("应用关闭，刷写剩余消费埋点。缓冲区 {} 条，累计丢弃 {} 条，累计落库失败 {} 次",
                buffer.size(), droppedCount.get(), flushFailCount.get());
        flush();
    }

    /**
     * 取出缓冲区内容并去重。
     *
     * <p><b>去重是必须的：</b>MySQL 的 {@code ON DUPLICATE KEY UPDATE} 在同一条
     * INSERT 语句内部遇到两行相同唯一键时同样会报错（它只在跨语句时才能合并）。
     * 同一批里出现重复的 {@code (message_id, consumer_group)} 只有一种可能——
     * 同一条消息在两次尝试中被连续记录——保留最后一条即可。
     */
    private List<MqConsumeLog> drain() {
        Map<String, MqConsumeLog> deduped = new LinkedHashMap<>();
        MqConsumeLog entity;
        while ((entity = buffer.poll()) != null) {
            bufferSize.decrementAndGet();
            deduped.put(entity.getMessageId() + "|" + entity.getConsumerGroup(), entity);
        }
        return new ArrayList<>(deduped.values());
    }

    /** error_msg 列是 VARCHAR(1024)，超长会让整批插入失败（严格模式下报错而非截断） */
    private String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
