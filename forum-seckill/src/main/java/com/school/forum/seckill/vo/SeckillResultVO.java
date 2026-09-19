package com.school.forum.seckill.vo;

/**
 * 抢购结果。前端在拿到 {@link #status} 之前不会知道自己是「排队中」还是「没抢到」。
 *
 * <p><b>为什么必须有 PENDING 这个中间态：</b>抢购是「Redis 预扣成功后立刻返回」，
 * 订单由 MQ 消费者异步落库。这段时间里如果没有一个明确的「处理中」，
 * 前端只能在「什么都不显示」和「以为失败了」之间二选一——前者让用户重复点击，
 * 后者让用户投诉。秒杀场景下用户对短暂等待有心理预期，前提是系统把这个状态说出来。
 */
public record SeckillResultVO(String status,
                              String orderNo,
                              String message,
                              SeckillOrderVO order) {

    /** 排队中，继续轮询 */
    public static final String PENDING = "PENDING";
    /** 落库成功，订单已生成 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败（Redis 与 DB 漂移等），不会再变 */
    public static final String FAILED = "FAILED";
    /** 超时关闭（未支付） */
    public static final String TIMEOUT = "TIMEOUT";
    /** 用户主动取消 */
    public static final String CANCELLED = "CANCELLED";

    public static SeckillResultVO pending(String orderNo) {
        return new SeckillResultVO(PENDING, orderNo, "排队中，正在为你生成订单", null);
    }

    /** 结果键不存在时的兜底：既没抢到、也没有排队记录 */
    public static SeckillResultVO none() {
        return new SeckillResultVO("NONE", null, "没有进行中的抢购", null);
    }
}
