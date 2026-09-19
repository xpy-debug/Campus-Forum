package com.school.forum.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.forum.common.constant.MqTopic;
import com.school.forum.common.constant.RedisKey;
import com.school.forum.infrastructure.mq.core.EventPublisher;
import com.school.forum.notification.api.SelfTestApi;
import com.school.forum.notification.api.SelfTestResult;
import com.school.forum.notification.event.SelfTestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 自检实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfTestApiImpl implements SelfTestApi {

    private static final String KEY_LAST = RedisKey.PREFIX + "selftest:last";
    private static final String KEY_COUNT = RedisKey.PREFIX + "selftest:count";

    /** 等待消费的最长时间。压测环境里 MQ 积压严重时，2 秒可能不够，这是刻意设的保守值 */
    private static final long AWAIT_TIMEOUT_MILLIS = 2000;
    private static final long POLL_INTERVAL_MILLIS = 50;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final EventPublisher eventPublisher;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public SelfTestResult publishAndAwait(String note) {
        SelfTestEvent event = new SelfTestEvent(note == null ? "manual" : note);
        eventPublisher.publish(MqTopic.SELF_TEST, event);

        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            JsonNode last = readLast();
            // 必须比对 eventId 而不是「有没有记录」：
            // 上一次自检留下的记录还在 Redis 里，只看存在性会把旧记录误判成本次的消费结果。
            if (last != null && event.getEventId().equals(last.path("eventId").asText())) {
                return buildResult(event, last, true);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 超时。注意这里返回的是 consumed=false 而不是抛异常——
        // 自检没通过本身就是一个有效的结果，调用方需要看到
        // 「provider 是哪个、消息发出去时的 ID 是什么」，才能继续排查。
        log.warn("自检消息在 {} ms 内未被消费。provider={}, eventId={}",
                AWAIT_TIMEOUT_MILLIS, eventPublisher.provider(), event.getEventId());
        return new SelfTestResult(eventPublisher.provider(), event.getEventId(),
                event.getNote(), false, -1, null, readCount());
    }

    @Override
    public SelfTestResult lastResult() {
        JsonNode last = readLast();
        if (last == null) {
            return new SelfTestResult(eventPublisher.provider(), null, null, false, -1, null, 0);
        }
        return new SelfTestResult(
                eventPublisher.provider(),
                last.path("eventId").asText(),
                last.path("note").asText(),
                true,
                last.path("e2eMillis").asLong(),
                formatTime(last.path("receivedAtMillis").asLong()),
                readCount());
    }

    private SelfTestResult buildResult(SelfTestEvent event, JsonNode last, boolean consumed) {
        return new SelfTestResult(
                eventPublisher.provider(),
                event.getEventId(),
                event.getNote(),
                consumed,
                last.path("e2eMillis").asLong(),
                formatTime(last.path("receivedAtMillis").asLong()),
                readCount());
    }

    private JsonNode readLast() {
        try {
            String json = stringRedisTemplate.opsForValue().get(KEY_LAST);
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("读取自检记录失败", e);
            return null;
        }
    }

    private long readCount() {
        try {
            String count = stringRedisTemplate.opsForValue().get(KEY_COUNT);
            return count == null ? 0 : Long.parseLong(count);
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatTime(long millis) {
        return millis <= 0 ? null : TIME_FORMAT.format(Instant.ofEpochMilli(millis));
    }
}
