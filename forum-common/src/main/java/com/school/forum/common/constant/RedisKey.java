package com.school.forum.common.constant;

/**
 * Redis key 规范。
 *
 * <p><b>命名格式：</b>{@code forum:<域>:<对象>[:<标识>]}，全部小写，冒号分隔。
 * 冒号分隔不只是习惯——Redis Cluster 用「hash tag」（{@code {}} 包裹的部分）决定
 * 分片，用冒号分段可以让同一业务对象的多个 key 在人工排查时聚在一起。
 *
 * <p><b>关于大 key：</b>所有可能随用户数增长的集合类 key（点赞集合、关注集合）都必须
 * 带明确的过期时间或分片策略，不能写成永久 key。ZSet 超过 5000 个元素后 ZRANGE 的
 * 延迟会明显上升。
 */
public final class RedisKey {

    private RedisKey() {
    }

    /** 全局前缀，便于用 {@code SCAN forum:*} 排查问题，也便于与同实例的其他应用隔离 */
    public static final String PREFIX = "forum:";

    // ==================== 用户域 ====================

    /** 登录 token -> 用户信息。{@code forum:token:{token}}，TTL 与 token 有效期一致 */
    public static String token(String token) {
        return PREFIX + "token:" + token;
    }

    /**
     * Access Token 白名单。{@code forum:auth:access:{jti}}，值为用户 ID，TTL 30 分钟。
     *
     * <p>用 jti（令牌唯一 ID）而不是令牌串本身做 key：令牌串长达数百字符，
     * 直接当 key 既浪费内存又会在慢查询日志里刷屏。jti 是签发时生成的一次性随机值，
     * 用它做 key 同样能保证「只有服务端登记过的令牌才有效」。
     *
     * <p>登出或踢人时删掉这个 key，令牌立即失效——这是纯无状态 JWT 做不到的。
     */
    public static String accessToken(String jti) {
        return PREFIX + "auth:access:" + jti;
    }

    /** Refresh Token 白名单。{@code forum:auth:refresh:{jti}}，TTL 7 天 */
    public static String refreshToken(String jti) {
        return PREFIX + "auth:refresh:" + jti;
    }

    /** 注册防重锁。{@code forum:lock:user:register:{username}}，见设计文档《04》5.5 */
    public static String registerLock(String username) {
        return LOCK_PREFIX + "user:register:" + username;
    }

    /** 用户信息缓存。{@code forum:user:info:{userId}} */
    public static String userInfo(Long userId) {
        return PREFIX + "user:info:" + userId;
    }

    /** 登录失败次数计数。{@code forum:user:login:fail:{username}}，TTL 15 分钟 */
    public static String loginFail(String username) {
        return PREFIX + "user:login:fail:" + username;
    }

    /** 用户关注列表（Set）。{@code forum:user:follow:{userId}} */
    public static String userFollow(Long userId) {
        return PREFIX + "user:follow:" + userId;
    }

    /** 用户粉丝列表（Set）。{@code forum:user:fans:{userId}} */
    public static String userFans(Long userId) {
        return PREFIX + "user:fans:" + userId;
    }

    // ==================== 论坛域 ====================

    /** 帖子详情缓存。{@code forum:post:detail:{postId}} */
    public static String postDetail(Long postId) {
        return PREFIX + "post:detail:" + postId;
    }

    /**
     * 帖子计数。{@code forum:post:count:{postId}}，Hash 结构，
     * field 为 {@code like}/{@code comment}/{@code collect}/{@code view}。
     *
     * <p>这是「Redis 为实时真相」的落点：点赞后立刻 HINCRBY，用户看到的是准确值；
     * MySQL 中的 like_count 由定时任务异步刷回，是最终真相。
     */
    public static String postCount(Long postId) {
        return PREFIX + "post:count:" + postId;
    }

    /** 板块热榜（ZSet）。{@code forum:board:hot:{boardId}}，score 为热度分 */
    public static String boardHot(Long boardId) {
        return PREFIX + "board:hot:" + boardId;
    }

    /** 全站热榜（ZSet）。{@code forum:hot:global} */
    public static final String HOT_GLOBAL = PREFIX + "hot:global";

    /** 搜索热词（ZSet）。{@code forum:search:hot} */
    public static final String SEARCH_HOT = PREFIX + "search:hot";

    // ==================== 互动域 ====================

    /**
     * 点赞用户集合（Set）。{@code forum:like:target:{post|comment}:{targetId}:users}
     *
     * <p>帖子点赞与评论点赞共用一套 key 结构，靠 {@code targetType} 区分——
     * 与 {@code t_user_like} 用一张表存两类点赞是同一个取舍：
     * 判重、计数、对账的代码可以完全复用一套。
     *
     * <p><b>为什么用 Set 而不是 Bitmap：</b>Bitmap 内存只有 Set 的千分之一，
     * 但「取出点赞用户列表」（详情页展示点赞头像）需要遍历整个 Id 空间还原 userId，
     * 复杂度是 O(Id空间) 而非 O(点赞数)。这个高频需求让 Bitmap 的省内存优势不划算。
     *
     * <p>判重靠 {@code SISMEMBER} 而非 {@code SCARD}：前者 O(1)，后者 O(1) 但
     * 在 Set 很大时仍会占用主线程时间片，且它给不出「是谁」。
     */
    public static String likeUsers(int targetType, Long targetId) {
        return PREFIX + "like:target:" + targetTypeName(targetType) + ":" + targetId + ":users";
    }

    /**
     * 点赞数实时值（String）。{@code forum:like:target:{post|comment}:{targetId}:count}
     *
     * <p>这是「Redis 为实时真相」的落点：点赞后立刻 INCR，用户看到的是准确值；
     * MySQL 的 {@code like_count} 由 MQ 消费者异步刷写，是最终真相。
     */
    public static String likeCount(int targetType, Long targetId) {
        return PREFIX + "like:target:" + targetTypeName(targetType) + ":" + targetId + ":count";
    }

    /**
     * 点赞事件的最后处理时间戳。{@code forum:like:ts:{type}:{id}:{userId}}
     *
     * <p><b>解决的是消息乱序问题。</b>点赞与取消点赞是两个不同的 topic，
     * 而跨 topic 没有任何顺序保证（Kafka 更是连分区都不同）。用户快速
     * 「点赞→取消」时，若取消先于点赞被消费，最终落库结果会变成「已点赞」——
     * 与用户看到的界面正好相反。消费端比较本 key 的值，只处理更新的消息。
     */
    public static String likeTimestamp(int targetType, Long targetId, Long userId) {
        return PREFIX + "like:ts:" + targetTypeName(targetType) + ":" + targetId + ":" + userId;
    }

    /** 用户点赞过的目标集合（Set），用于「我的点赞」列表。{@code forum:like:user:{userId}} */
    public static String userLike(Long userId) {
        return PREFIX + "like:user:" + userId;
    }

    /**
     * 楼层号发号器。{@code forum:comment:floor:{postId}}，由 {@code INCR} 生成楼层。
     *
     * <p><b>不能用 {@code COUNT(*)} 生成楼层号：</b>并发下两个请求会取到同一个值，
     * 出现两个「3 楼」。{@code INCR} 是原子的，天然无竞争。
     */
    public static String commentFloor(Long postId) {
        return PREFIX + "comment:floor:" + postId;
    }

    /** 帖子评论列表首屏缓存。{@code forum:comment:first:{postId}} */
    public static String commentFirstPage(Long postId) {
        return PREFIX + "comment:first:" + postId;
    }

    /**
     * 浏览数缓冲计数。{@code forum:post:view:{postId}}
     *
     * <p>详情页不做 {@code UPDATE t_post SET view_count = view_count + 1}——
     * 那会让每次浏览都产生一次写操作与行锁竞争。改为在此 INCR，
     * 由定时任务批量回写。回写用 {@code DECRBY} 而非 {@code DEL}，
     * 否则回写过程中新产生的浏览会被一并抹掉。
     */
    public static String postView(Long postId) {
        return PREFIX + "post:view:" + postId;
    }

    /** 浏览去重标记。{@code forum:post:viewed:{postId}:{userId}}，TTL 30 分钟 */
    public static String postViewed(Long postId, Long userId) {
        return PREFIX + "post:viewed:" + postId + ":" + userId;
    }

    /**
     * 待回写浏览数的帖子集合（Set）。{@code forum:post:view:dirty}
     *
     * <p><b>为什么需要它：</b>回写任务必须知道「哪些帖子的计数变了」。
     * 用 {@code KEYS forum:post:view:*} 扫描在生产环境是禁止的——它是 O(N) 且会阻塞
     * 整个 Redis 实例。改成每次浏览时把 postId 记进这个集合，任务只处理集合里的成员，
     * 复杂度与实际发生变化的帖子数成正比。
     */
    public static String postViewDirty() {
        return PREFIX + "post:view:dirty";
    }

    /**
     * 发帖接口的幂等令牌。{@code forum:idem:post:{Idempotency-Key}}，值为已创建的帖子 ID，TTL 24 小时。
     *
     * <p>前端在提交按钮按下时生成一个 UUID 并复用同一次提交，网络重试也不会重复发帖。
     * 与业务唯一索引的区别：唯一索引防的是「重复数据」，幂等令牌防的是「重复动作」——
     * 帖子本来就允许多次发同样的内容，只有幂等令牌能区分二者。
     */
    public static String postIdempotent(String key) {
        return PREFIX + "idem:post:" + key;
    }

    // ==================== 通知域 ====================

    /** 用户未读通知数。{@code forum:notify:unread:{userId}} */
    public static String unreadCount(Long userId) {
        return PREFIX + "notify:unread:" + userId;
    }

    // ==================== 营销域（秒杀） ====================

    /**
     * 秒杀库存。{@code forum:seckill:stock:{activityId}}，String 类型存剩余数量。
     *
     * <p>用 String 而非 Hash：Lua 脚本里对单值做 DECR 比 HINCRBY 少一层寻址，
     * 在 10000 QPS 下这点差异会被放大。且库存只有一个值，不需要 Hash 的多字段能力。
     */
    public static String seckillStock(Long activityId) {
        return PREFIX + "seckill:stock:" + activityId;
    }

    /**
     * 秒杀已抢用户（Hash）。{@code forum:seckill:bought:{activityId}}，
     * field 为 userId，value 为已抢数量。
     *
     * <p><b>用 Hash 而不是 Set：</b>{@code HGET} 判 0/1 已经能满足「每人限购 1 件」，
     * 而 {@code HINCRBY} 让 {@code per_user_limit} 变成一个可配置参数——
     * 将来放开限购要改的只是配置与订单表的唯一索引，不必动 Lua 脚本和数据结构。
     *
     * <p><b>取消订单时<em>不</em>删除本 key 中的成员。</b>「每人限购 1 件」是
     * <b>活动期内的一次性资格</b>，不是「同时最多持有 1 件」：取消只退还库存，
     * 不退还资格。因此用户取消后再次抢购应得到 15002，
     * 而不是「扣了库存却发现 DB 唯一索引插不进去」。
     */
    public static String seckillBought(Long activityId) {
        return PREFIX + "seckill:bought:" + activityId;
    }

    /**
     * 秒杀活动预热标记。{@code forum:seckill:warmup:{activityId}}
     * 值为 1 表示库存已加载到 Redis。
     *
     * <p><b>它的作用是让「没抢到」和「系统没准备好」说出不同的话：</b>
     * 缺这个标记时抢购返回 15005（活动太火爆），而不是 15001（已抢光）。
     * 后者会让用户以为自己来晚了，而实际上是活动还没预热。
     */
    public static String seckillWarmup(Long activityId) {
        return PREFIX + "seckill:warmup:" + activityId;
    }

    /**
     * 秒杀活动信息快照（Hash）。{@code forum:seckill:activity:info:{activityId}}
     *
     * <p>预热时由活动行写入，抢购时用它判时间窗与状态，
     * <b>让抢购这条 P99 &lt; 50ms 的路径上没有任何一次数据库查询</b>。
     */
    public static String seckillActivityInfo(Long activityId) {
        return PREFIX + "seckill:activity:info:" + activityId;
    }

    /**
     * 用户在某场活动里抢到的订单号。{@code forum:seckill:order:{activityId}:{userId}}
     *
     * <p>两个用途，缺一不可：
     * <ol>
     *   <li><b>幂等重放</b>——Lua 里先查本 key，命中就直接把它返回，
     *       于是「连点十次」与「网络重试」拿到的都是同一张单，不会再扣一次库存；</li>
     *   <li><b>结果锚点</b>——消息里带的 orderNo 与这里一致，
     *       消费者据此落库，用户据此查询进度。</li>
     * </ol>
     * 注意它<b>不是</b>「用户是否取消」的判断依据：取消是 DB 侧的状态流转，
     * 靠本 key 的存在与否去推断取消会引入必然的竞态（读到了、写库前用户取消了）。
     */
    public static String seckillOrderNo(Long activityId, Long userId) {
        return PREFIX + "seckill:order:" + activityId + ":" + userId;
    }

    /**
     * 秒杀订单号的日内序号。{@code forum:seckill:order:no:{yyyyMMdd}}，由 Lua 内 {@code INCR} 生成。
     *
     * <p>订单号 = {@code S + yyyyMMdd + 6 位日内序号}。在 Lua 里生成而不是应用层预生成：
     * 失败路径不消耗序号，且「生成 + 预扣 + 记录」严格原子，
     * 不会出现「扣了库存却没拿到单号」。TTL 2 天，覆盖跨天即可。
     */
    public static String seckillOrderNoSeq(String yyyyMMdd) {
        return PREFIX + "seckill:order:no:" + yyyyMMdd;
    }

    /**
     * 秒杀结果（前端轮询用）。{@code forum:seckill:result:{activityId}:{userId}}
     *
     * <p>取值 {@code PENDING} / {@code SUCCESS} / {@code FAILED} / {@code TIMEOUT} / {@code CANCELLED}。
     * 抢购是异步落库的，用户在订单生成前必须先看到一个「排队中」——
     * 没有这个中间态，前端只能在「什么都不显示」和「以为失败了」之间二选一。
     */
    public static String seckillResult(Long activityId, Long userId) {
        return PREFIX + "seckill:result:" + activityId + ":" + userId;
    }

    // ==================== 积分与商城域 ====================

    /**
     * 签到位图。{@code forum:points:signin:{userId}:{yyyyMM}}，Bitmap 结构，
     * 第 N 位（offset = 日 - 1）为 1 表示当月第 N 天已签到。
     *
     * <p><b>为什么用 Bitmap 而不是 Set 或 MySQL：</b>单用户单月只要 4 字节
     * （Set 存 31 个日期字符串约 1.5 KB，是它的 375 倍），而更关键的是
     * {@code SETBIT} 的<b>返回值就是幂等信号</b>——返回 0 表示本次是首次签到，
     * 返回 1 表示今天已签。一条命令同时完成「判断」与「写入」，
     * 不需要「先 GETBIT 再 SETBIT」那种在并发下必然双双通过的两步写法。
     *
     * <p><b>它只是加速器，不是真相。</b>真相是 {@code t_user_signin}
     * （{@code uk_user_date} 保证不重复），位图可能因 Redis 重启、误删、
     * 或「置位后事务回滚」而与库不一致，由每日 04:00 的修复任务按库重建。
     */
    public static String signinBitmap(Long userId, String yearMonth) {
        return PREFIX + "points:signin:" + userId + ":" + yearMonth;
    }

    /**
     * 积分余额缓存（可选）。{@code forum:points:balance:{userId}}
     *
     * <p><b>当前实现刻意不使用它。</b>余额是强一致要求的数据，缓存意味着
     * 「扣了积分但缓存没失效」的窗口；而走主键查单行本来就是微秒级，
     * 收益不足以抵消这个风险。真正需要缓存的是「积分排行榜」那类读多写更多的场景，
     * 届时应该缓存排序结果（ZSet），而不是缓存单用户余额。
     */
    public static String pointsBalance(Long userId) {
        return PREFIX + "points:balance:" + userId;
    }

    /**
     * 兑换订单号的日内序号。{@code forum:mall:order:no:{yyyyMMdd}}，由 {@code INCR} 生成。
     *
     * <p>{@code INCR} 而不是「查当天最大单号 + 1」：后者在两个请求同时下单时
     * 会取到同一个值。TTL 2 天——覆盖跨天即可，留一天余量便于排查。
     * 订单号 = {@code M + yyyyMMdd + 6 位日内序号}，位数固定，故按字典序即按时间序。
     */
    public static String mallOrderNoSeq(String yyyyMMdd) {
        return PREFIX + "mall:order:no:" + yyyyMMdd;
    }

    // ==================== 分布式锁 ====================

    /** 业务锁统一前缀，便于用 {@code forum:lock:*} 观察锁竞争情况 */
    public static final String LOCK_PREFIX = PREFIX + "lock:";

    /** 秒杀活动维度的锁。避免同一活动的并发操作（如手动补库存）互相干扰 */
    public static String seckillLock(Long activityId) {
        return LOCK_PREFIX + "seckill:" + activityId;
    }

    /**
     * 秒杀预热任务的锁。{@code forum:lock:seckill:warmup:{activityId}}
     *
     * <p>预热是多实例部署下唯一会「同一活动被加载多次」的场景。
     * 虽然 {@code SETNX} 本身已经能防重复写入，但加锁能省掉一批无谓的
     * 序列化与网络往返，也让「谁在预热」这件事变得可观察。
     */
    public static String seckillWarmupLock(Long activityId) {
        return LOCK_PREFIX + "seckill:warmup:" + activityId;
    }

    /** 秒杀库存对账任务的锁。{@code forum:lock:stock:reconcile:{activityId}} */
    public static String seckillReconcileLock(Long activityId) {
        return LOCK_PREFIX + "stock:reconcile:" + activityId;
    }

    /** 缓存重建锁。防止热点 key 失效瞬间大量请求同时打数据库 */
    public static String rebuildLock(String cacheKey) {
        return LOCK_PREFIX + "rebuild:" + cacheKey;
    }

    /** 定时任务锁。保证集群中只有一个实例执行某个任务 */
    public static String jobLock(String jobName) {
        return LOCK_PREFIX + "job:" + jobName;
    }

    /**
     * 月度签到奖励结算的任务锁。{@code forum:lock:points:bonus:{yyyy-MM}}
     *
     * <p><b>这个锁不是幂等性的依靠，只是省一次无用功。</b>该任务真正的保障是
     * 「每日重跑上月 + {@code uk_user_biz} 唯一索引」——锁只能防「同时跑」，
     * 防不住「漏跑」，而这个任务最坏的情况恰恰是根本没执行。
     * 加锁的唯一目的是避免多实例同时发起同一批查询。
     */
    public static String pointsBonusLock(String yearMonth) {
        return LOCK_PREFIX + "points:bonus:" + yearMonth;
    }

    // ==================== 辅助 ====================

    /**
     * 把点赞目标类型的数字编码转为可读名称，让 Redis key 自解释。
     * 排查问题时看到 {@code forum:like:post:100} 比 {@code forum:like:1:100} 直观得多。
     *
     * <p>同时作为点赞类消息的分区键前缀使用（如 {@code post:123}）：
     * 两类目标的名字只应有一处定义，Redis key 与 MQ 分区键各写一套映射，
     * 迟早会出现「key 里叫 post、消息里叫 article」这种要靠猜才能对上号的情况。
     */
    public static String targetTypeName(int targetType) {
        return switch (targetType) {
            case 1 -> "post";
            case 2 -> "comment";
            case 3 -> "user";
            default -> String.valueOf(targetType);
        };
    }
}
