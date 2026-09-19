package com.school.forum.infrastructure.mq.outbox;

import com.school.forum.common.constant.RedisKey;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.infrastructure.mq.mapper.MqOutboxMapper;
import com.school.forum.infrastructure.mq.core.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表调度器：把到期的消息投递到 MQ。
 *
 * <p>承担两件事：
 * <ol>
 *   <li><b>可靠投递兜底</b>——业务侧「先写库、再发消息」的链路中，若进程在发消息前崩溃，
 *       消息仍留在表里，由本调度器补发。</li>
 *   <li><b>Kafka 的延迟消息</b>——见
 *       {@link com.school.forum.infrastructure.mq.kafka.KafkaEventPublisher#publishDelay}。</li>
 * </ol>
 *
 * <p><b>多实例下的重复投递防护做了两层：</b>
 * <ul>
 *   <li>Redisson 分布式锁：同一时刻只有一个实例在扫描，避免无谓的竞争</li>
 *   <li>CAS 更新：{@code UPDATE ... WHERE status = 期望值}，只有影响行数为 1 的那个
 *       实例才算真正抢到这条消息。锁可能因为 GC 停顿、网络分区而短暂失效，
 *       <b>数据库层的 CAS 才是最终保证</b>，锁只是减少冲突。</li>
 * </ul>
 * 即便如此，投递仍可能重复（发出消息后、更新状态前崩溃），
 * 所以消费端必须幂等——这也是整条链路只承诺 at-least-once 的原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "forum.mq.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqOutboxDispatcher {

    private final MqOutboxMapper outboxMapper;
    private final EventPublisher eventPublisher;
    private final MqProperties properties;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${forum.mq.outbox.scan-interval-ms:1000}")
    public void dispatch() {
        RLock lock = redissonClient.getLock(RedisKey.jobLock("mq-outbox-dispatch"));
        boolean locked = false;
        try {
            // tryLock 不等待：抢不到说明别的实例正在处理，本轮直接跳过，
            // 比排队等待更合适——下一个调度周期很快就会再来。
            locked = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            doDispatch();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("本地消息表调度异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doDispatch() {
        List<MqOutboxMessage> due = outboxMapper.selectDue(
                LocalDateTime.now(), properties.getOutbox().getBatchSize());
        if (due.isEmpty()) {
            return;
        }

        int sent = 0;
        for (MqOutboxMessage message : due) {
            // CAS 抢占：把状态从「待发送/发送失败」改成「已发送」。
            // 影响行数为 0 说明别的实例已经处理了这条，跳过。
            int claimed = outboxMapper.casUpdateStatus(
                    message.getId(),
                    message.getStatus(),
                    MqOutboxMessage.STATUS_SENT,
                    0,
                    message.getErrorMsg() == null ? "" : message.getErrorMsg(),
                    // 成功投递后把 next_retry_time 推到很远的未来，
                    // 避免它继续命中 selectDue 的扫描条件
                    LocalDateTime.now().plusYears(1));
            if (claimed == 0) {
                continue;
            }

            try {
                // 直接透传已序列化好的 payload，不重新序列化。
                // 这里从表里反序列化再重新序列化一遍既浪费 CPU，
                // 也有可能在两次序列化之间因为类结构变化而产生不一致。
                eventPublisher.publishRaw(message.getTopic(), message.getRoutingKey(), message.getPayload());
                sent++;
            } catch (Exception e) {
                log.error("本地消息表投递失败。id={}, topic={}, messageId={}",
                        message.getId(), message.getTopic(), message.getMessageId(), e);
                handleFailure(message, e);
            }
        }
        if (sent > 0) {
            log.info("本地消息表投递完成 {} 条（本轮扫描 {} 条）", sent, due.size());
        }
    }

    /** 投递失败：指数退避后重试，超过上限则标记为发送失败等待人工介入 */
    private void handleFailure(MqOutboxMessage message, Exception e) {
        int nextRetry = message.getRetryCount() + 1;
        boolean giveUp = nextRetry >= message.getMaxRetry();

        // 指数退避 2^n 秒：2s, 4s, 8s, 16s ...
        // 线性退避在持续故障时重试过于频繁，会持续给下游施压，把「短暂抖动」拖成「长时间不可用」。
        long delaySeconds = (long) Math.pow(2, nextRetry);

        // 放弃时把下次投递时间推到很远的未来，让它彻底不再命中 selectDue 的扫描条件。
        // 否则 status=2 仍在扫描范围内，会有下一次、再下一次……
        // 「重试超限」就变成了「永远重试」，指数退避也救不回来。
        LocalDateTime nextRetryTime = giveUp
                ? LocalDateTime.now().plusYears(1)
                : LocalDateTime.now().plusSeconds(delaySeconds);

        // 从「已发送」（上一步抢占时写入的状态）转到「发送失败」
        outboxMapper.casUpdateStatus(
                message.getId(),
                MqOutboxMessage.STATUS_SENT,
                MqOutboxMessage.STATUS_SEND_FAILED,
                1,
                truncate(e.getMessage()),
                nextRetryTime);

        if (giveUp) {
            log.error("本地消息表消息重试超限，转人工处理。id={}, messageId={}, retry={}",
                    message.getId(), message.getMessageId(), nextRetry);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
