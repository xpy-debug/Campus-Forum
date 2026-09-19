package com.school.forum.notification.api;

/**
 * 链路自检的对外契约。
 *
 * <p><b>这个接口是本项目「模块间只能通过 api 包通信」规则的示范：</b>
 * {@code forum-boot} 里的 Controller 只依赖这个接口，看不到
 * {@code SelfTestEvent}、{@code SelfTestEventHandler} 这些内部实现类。
 * 将来通知域要拆成独立服务时，只需要把本接口的实现换成 HTTP 客户端，
 * 调用方一行都不用改。
 *
 * <p><b>api 包里只放接口和 DTO，不放任何实现。</b>
 * 判断标准很简单：如果把本模块的实现代码整体删掉、只保留 api 包，
 * 项目仍然应该能<b>编译通过</b>（运行期当然会因为没有实现而失败）。
 * 这条标准能有效防止「api 包里偷偷放一个静态工具类」这类侵蚀。
 */
public interface SelfTestApi {

    /**
     * 发一条自检消息，并等待它被消费。
     *
     * <p>本方法是<b>同步阻塞</b>的，最多等待约 2 秒。
     * 这违背了本系统「异步链路不阻塞」的原则，但这里是有意为之：
     * 自检的价值就在于给出一个明确的「通 / 不通」结论，
     * 如果也做成异步，调用方就得自己轮询，反而把复杂度推给了使用者。
     * 它只用于人工验证，不在任何业务链路里被调用，所以阻塞是可以接受的。
     *
     * @param note 说明文字，用于区分触发来源
     * @return 自检结果，含是否已被消费、端到端耗时、当前生效的 MQ 实现
     */
    SelfTestResult publishAndAwait(String note);

    /**
     * 读取最近一次自检的消费结果，不发送新消息。
     * <p>用于「刚才那次是异步的，现在看看到了没」以及长时间观察链路是否还活着。
     */
    SelfTestResult lastResult();
}
