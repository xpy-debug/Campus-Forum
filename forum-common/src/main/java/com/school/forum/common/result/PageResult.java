package com.school.forum.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 *
 * <p>同时支持两种分页模式，二者不可混用：
 *
 * <ul>
 *   <li><b>游标分页</b>（信息流，默认）：使用 {@code nextCursor} + {@code hasMore}。
 *       不返回总数，因为 {@code COUNT(*)} 在深分页时本身就是一次昂贵查询，
 *       而信息流产品也不需要「共 N 条」这种信息。</li>
 *   <li><b>页码分页</b>（后台列表）：使用 {@code total} + {@code pages}。
 *       后台数据量可控且需要跳页，此时 COUNT 的代价可以接受。</li>
 * </ul>
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list = Collections.emptyList();

    /** 下一页游标。为 null 表示没有下一页 */
    private String nextCursor;

    /** 是否还有更多数据 */
    private boolean hasMore;

    /** 总条数。仅页码分页模式填充，游标分页模式为 null */
    private Long total;

    /** 总页数。仅页码分页模式填充 */
    private Long pages;

    // ==================== 游标分页 ====================

    /**
     * 构造游标分页结果。
     *
     * @param list       当前页数据。约定：调用方多查一条（size + 1）用于判断是否还有下一页，
     *                   本方法会截断到 size 条。这样比额外的 COUNT 查询便宜得多。
     * @param size       期望的每页条数
     * @param cursorFunc 从最后一条数据中提取游标值的函数
     */
    public static <T> PageResult<T> ofCursor(List<T> list, int size, Function<T, String> cursorFunc) {
        PageResult<T> result = new PageResult<>();
        if (list == null || list.isEmpty()) {
            result.setHasMore(false);
            return result;
        }

        boolean hasMore = list.size() > size;
        List<T> pageData = hasMore ? list.subList(0, size) : list;

        result.setList(pageData);
        result.setHasMore(hasMore);
        if (hasMore && cursorFunc != null) {
            result.setNextCursor(cursorFunc.apply(pageData.get(pageData.size() - 1)));
        }
        return result;
    }

    /** 构造空的游标分页结果 */
    public static <T> PageResult<T> empty() {
        return new PageResult<>();
    }

    // ==================== 页码分页 ====================

    /**
     * 构造页码分页结果。
     *
     * @param list  当前页数据
     * @param total 总条数
     * @param size  每页条数
     */
    public static <T> PageResult<T> ofPage(List<T> list, long total, long size) {
        PageResult<T> result = new PageResult<>();
        result.setList(list == null ? Collections.emptyList() : list);
        result.setTotal(total);
        result.setPages(size <= 0 ? 0 : (total + size - 1) / size);
        result.setHasMore(false);
        return result;
    }

    /**
     * 把当前结果映射为另一种元素类型，保留分页元数据。
     * 用于 Service 层返回 DO、Controller 层转换为 VO 的场景。
     */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        PageResult<R> mapped = new PageResult<>();
        mapped.setList(this.list.stream().map(mapper).toList());
        mapped.setNextCursor(this.nextCursor);
        mapped.setHasMore(this.hasMore);
        mapped.setTotal(this.total);
        mapped.setPages(this.pages);
        return mapped;
    }
}
