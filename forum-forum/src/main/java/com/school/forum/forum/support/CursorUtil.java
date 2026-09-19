package com.school.forum.forum.support;

import com.school.forum.common.exception.BizException;
import com.school.forum.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 * 游标编解码。
 *
 * <p><b>游标为什么必须带 id：</b>排序键是 {@code publish_time}，而 MySQL 的 DATETIME
 * 精度只到秒。同一秒内发的三条帖子排序键完全相同，只用一个时间做游标时，
 * 第二页的 {@code publish_time < ?} 会把剩下两条一起跳过——数据凭空消失。
 * 带上 id 作为决胜列，{@code (publish_time, id) < (?, ?)} 才是全序的。
 *
 * <p><b>为什么用 base64 而不是直接传两个参数：</b>把游标编码成一个不透明字符串之后，
 * 前端就不可能「自己拼一个游标出来」——它只能把服务端返回的 {@code nextCursor}
 * 原样回传。排序方式（按时间还是按热度）也就成了服务端可控的实现细节，
 * 不会因为前端传错参数而出现「热度榜单按时间翻页」这种诡异结果。
 */
@Slf4j
public final class CursorUtil {

    /** 分隔符用下划线：它不在 base64 的字符集里，解码后按它切分不会有歧义 */
    private static final String SEPARATOR = "_";

    private CursorUtil() {
    }

    /** 编码时间类游标，如 {@code (publish_time, id)} */
    public static String encode(LocalDateTime sortKey, Long id) {
        if (sortKey == null || id == null) {
            return null;
        }
        long epochMilli = sortKey.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return encodeRaw(epochMilli + SEPARATOR + id);
    }

    /** 编码数值类游标，如 {@code (hot_score, id)}。热度分是小数，用字符串原样传递避免精度丢失 */
    public static String encode(String sortKeyValue, Long id) {
        if (sortKeyValue == null || id == null) {
            return null;
        }
        return encodeRaw(sortKeyValue + SEPARATOR + id);
    }

    /**
     * 解码出游标中的 id（第二段）。排序键值由 {@link #decodeSortKey} 获取。
     *
     * @return 游标为空或格式非法时返回 null，表示「从头开始」。游标是客户端传来的
     * 不可信数据，格式错误时退回第一页比抛异常更合适——用户改坏了 URL 也只是看到第一页
     */
    public static Long decodeId(String cursor) {
        String[] parts = split(cursor);
        if (parts == null) {
            return null;
        }
        try {
            return Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解码出游标中的排序键值（第一段） */
    public static String decodeSortKey(String cursor) {
        String[] parts = split(cursor);
        return parts == null ? null : parts[0];
    }

    /** 解码时间类游标的排序键 */
    public static LocalDateTime decodeTime(String cursor) {
        String raw = decodeSortKey(cursor);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(raw)), ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] split(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(SEPARATOR);
            return parts.length == 2 ? parts : null;
        } catch (IllegalArgumentException e) {
            log.debug("游标解码失败，按第一页处理。cursor={}", cursor);
            return null;
        }
    }
}
