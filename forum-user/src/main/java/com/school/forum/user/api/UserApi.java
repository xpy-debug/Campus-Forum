package com.school.forum.user.api;

import java.util.Collection;
import java.util.Map;

/**
 * 用户域对其他模块的对外契约。
 *
 * <p>帖子列表要给每条帖子填作者昵称与头像。若 forum-forum 直接查 {@code t_user}，
 * 用户表的结构就被论坛域锁死了——以后想把用户拆成独立服务，
 * 得先把散落在各模块的 SQL 全部找出来改掉。走这个接口之后，
 * 拆分时只需要把实现换成 HTTP 客户端，调用方一行都不用动。
 *
 * <p><b>这里只放接口与 DTO，不放实现，也不要加静态工具方法。</b>
 * 判断标准：把 forum-user 的实现代码整体删掉、只留 api 包，项目仍应编译通过。
 */
public interface UserApi {

    /**
     * 查询单个用户的简介。
     *
     * @return 用户不存在时返回 null，由调用方决定如何兜底
     */
    UserBrief getBrief(Long userId);

    /**
     * 批量查询用户简介，返回 {@code userId -> UserBrief}。
     *
     * <p><b>为什么必须有批量版本：</b>帖子列表一页 20 条，若逐条调用
     * {@link #getBrief}，就是 20 次查询（典型的 N+1）。批量接口在实现里
     * 一次 {@code WHERE id IN (...)} 解决，这也是调用方唯一该用的方式。
     *
     * <p>不存在的 userId 不会出现在返回结果里，调用方需自行判空；
     * 传入空集合或 null 时返回空 Map。
     */
    Map<Long, UserBrief> batchGetBrief(Collection<Long> userIds);

    /**
     * 调整用户的发帖数。
     *
     * <p>论坛域发帖成功后要维护 {@code t_user.post_count}。这件事必须走本接口，
     * 不能让 forum-forum 直接 UPDATE t_user——那样用户表的结构就被论坛域锁死了，
     * 正是这个 api 包要防的事。
     *
     * @param delta 发帖为 1，删帖为 -1
     */
    void addPostCount(Long userId, int delta);
}
