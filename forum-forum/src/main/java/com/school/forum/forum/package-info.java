/**
 * 论坛核心域：板块、帖子、评论、点赞、收藏、标签、搜索。
 *
 * <p>本模块承担系统的主要读写流量，也是「分级一致性」策略落地最集中的地方：
 * <ul>
 *   <li><b>评论走同步强一致</b>——用户发完评论要立刻看到它出现在列表里，
 *       异步化带来的「我的评论去哪了」是体验灾难。</li>
 *   <li><b>点赞走异步最终一致</b>——点赞的目标是 P99 &lt; 100ms 且要扛 5000 QPS，
 *       允许计数有秒级延迟。Redis 是实时真相（立刻 HINCRBY 并返回），
 *       MySQL 是最终真相（定时批量刷回）。</li>
 * </ul>
 *
 * <p>包结构与模块约定见 {@code com.school.forum.user} 的 package-info。
 *
 * <p><b>本模块不依赖任何 MQ 客户端</b>：发消息只通过
 * {@code EventPublisher} 抽象，消费者只实现 {@code EventHandler}。
 * 这既是架构要求，也是「用同一份代码对比两种 MQ 性能」的前提。
 */
package com.school.forum.forum;
