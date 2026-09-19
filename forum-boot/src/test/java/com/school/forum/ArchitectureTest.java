package com.school.forum;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束测试。
 *
 * <p><b>为什么这些规则必须由测试来保证，而不是写在文档里：</b>
 * 模块化单体最大的失败模式是「目录划分得很漂亮，但依赖关系早就烂了」。
 * 开发者遇到工期压力时，最省事的做法就是绕过边界——直接注入隔壁模块的 service、
 * 直接查隔壁模块的表。这类代码在 code review 时很容易被放过去（因为它「能跑」），
 * 但积累几十处之后，拆微服务就变成了不可能完成的任务。
 *
 * <p>把规则写成测试之后，任何越界都会让构建失败，边界就从「靠自觉」
 * 变成了「靠编译器」。本项目的取舍很明确：<b>宁可构建失败，也不要边界腐化</b>。
 *
 * <p>测试放在 forum-boot 模块，因为只有它是唯一能看到全部模块的模块。
 */
@AnalyzeClasses(
        packages = "com.school.forum",
        // 不分析测试类自身：测试里出现跨模块调用是正常的（要构造测试数据）
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] BUSINESS_MODULES = {
            "com.school.forum.user..",
            "com.school.forum.forum..",
            "com.school.forum.notification..",
            "com.school.forum.seckill..",
            "com.school.forum.points..",
            "com.school.forum.admin.."
    };

    // ========================================================================
    // 规则一：MQ 抽象层不能被击穿
    // ========================================================================

    /**
     * <b>本项目的核心架构约束。</b>业务模块不得直接依赖任何 MQ 产品的客户端。
     *
     * <p>违反这条规则的后果不是「代码变丑」，而是<b>整个选型对比失去意义</b>：
     * 只要有一个业务类写了 {@code @KafkaListener} 或者注入了 {@code RabbitTemplate}，
     * 那么切换到另一个产品时就必须改业务代码。一旦两次压测跑的代码不一样，
     * 测出来的性能差异就分不清是「MQ 的差异」还是「两次代码的差异」——
     * 而分辨这两者恰恰是本项目要做的事。
     *
     * <p>正确写法：发消息用 {@code EventPublisher}，收消息实现 {@code EventHandler}。
     */
    @ArchTest
    static final ArchRule 业务模块不得直接依赖MQ客户端 =
            noClasses()
                    .that().resideInAnyPackage(BUSINESS_MODULES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.apache.kafka..",
                            "org.springframework.kafka..",
                            "org.springframework.amqp..",
                            "com.rabbitmq.."
                    )
                    .because("业务模块必须通过 EventPublisher / EventHandler 抽象收发消息，"
                            + "否则切换 MQ 就要改业务代码，两种实现的压测结果将不可比");

    // ========================================================================
    // 规则二：依赖方向不能反转
    // ========================================================================

    /**
     * forum-common 是最底层，不依赖项目内任何其他模块。
     * <p>它一旦依赖了上层，所有模块都会被卷进依赖环里，Maven 会直接报循环依赖而无法构建。
     */
    @ArchTest
    static final ArchRule common不得依赖其他模块 =
            noClasses()
                    .that().resideInAPackage("com.school.forum.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.school.forum.infrastructure..",
                            "com.school.forum.user..",
                            "com.school.forum.forum..",
                            "com.school.forum.notification..",
                            "com.school.forum.seckill..",
                            "com.school.forum.points..",
                            "com.school.forum.admin.."
                    )
                    .because("forum-common 是最底层模块，被所有模块依赖，不能反向依赖任何人");

    /**
     * forum-infrastructure 只提供技术能力（MQ 抽象、Redis、Web 通用配置），
     * 不能反过来依赖具体的业务模块。
     *
     * <p>反过来会怎样：infrastructure 引用了 forum-user 的类，
     * 那么所有依赖 infrastructure 的模块（包括 forum-seckill）都会被迫
     * 把 forum-user 拉进自己的依赖里，模块边界立刻失效。
     */
    @ArchTest
    static final ArchRule infrastructure不得依赖业务模块 =
            noClasses()
                    .that().resideInAPackage("com.school.forum.infrastructure..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.school.forum.user..",
                            "com.school.forum.forum..",
                            "com.school.forum.notification..",
                            "com.school.forum.seckill..",
                            "com.school.forum.points..",
                            "com.school.forum.admin.."
                    )
                    .because("基础设施层提供的是通用能力，依赖具体业务会让它失去复用价值"
                            + "，并让所有下游模块被动卷入业务依赖");

    // ========================================================================
    // 规则三：跨模块只能访问 api 包
    // ========================================================================

    /**
     * forum-admin 是唯一依赖其他业务模块的模块，且只能访问对方的 api 包。
     *
     * <p>后台天然有「什么数据都想直接查」的倾向。如果放任它直接引用
     * forum-forum 的 mapper，那张表的结构就被后台锁死了——
     * 以后想给帖子表加字段、拆表、改状态机，都得先确认后台有没有在用。
     * 这种隐式耦合最难发现，也最难解除。
     */
    @ArchTest
    static final ArchRule admin只能通过api包访问其他业务模块 =
            noClasses()
                    .that().resideInAPackage("com.school.forum.admin..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.school.forum.user.entity..",
                            "com.school.forum.user.mapper..",
                            "com.school.forum.user.service..",
                            "com.school.forum.user.controller..",
                            "com.school.forum.forum.entity..",
                            "com.school.forum.forum.mapper..",
                            "com.school.forum.forum.service..",
                            "com.school.forum.forum.controller..",
                            "com.school.forum.seckill.entity..",
                            "com.school.forum.seckill.mapper..",
                            "com.school.forum.seckill.service..",
                            "com.school.forum.seckill.controller..",
                            "com.school.forum.points.entity..",
                            "com.school.forum.points.mapper..",
                            "com.school.forum.points.service..",
                            "com.school.forum.points.controller.."
                    )
                    .because("跨模块直接访问对方的 entity/mapper/service 会把内部实现锁死，"
                            + "应当改为调用对方 api 包下的接口");

    /** 模块之间的依赖不能成环。有环意味着「这几个模块其实是一个模块」，拆分无从谈起 */
    @ArchTest
    static final ArchRule 模块之间不得存在循环依赖 =
            SlicesRuleDefinition.slices()
                    .matching("com.school.forum.(*)..")
                    .should().beFreeOfCycles()
                    .because("循环依赖意味着模块边界划分错误，且会导致无法单独拆分任何一个模块");

    // ========================================================================
    // 规则四：模块内部的层次约束
    // ========================================================================

    /**
     * Controller 不得直接依赖 Mapper。
     *
     * <p>这是最常见的「图省事」写法：接口只是把一条记录查出来返回，
     * 直接调 mapper 看起来少写一层。代价是事务边界丢失、
     * 缓存逻辑被绕过、参数校验无处安放，而且这段逻辑无法被复用——
     * 将来定时任务或另一个接口需要同样的数据，只能再写一遍 SQL。
     */
    @ArchTest
    static final ArchRule controller不得直接依赖mapper =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..mapper..")
                    .because("数据访问必须经过 service 层，否则事务与缓存逻辑会被绕过");

    /**
     * Entity 是数据库表的映射，不得依赖 Service / Controller / Mapper。
     *
     * <p>Entity 一旦引用 Service，就等于把「表结构」和「业务逻辑」焊死，
     * 任何业务逻辑的改动都会牵动实体类，而实体类又被所有层引用，
     * 改动范围会迅速失控。
     */
    @ArchTest
    static final ArchRule entity不得依赖上层 =
            noClasses()
                    .that().resideInAPackage("..entity..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..service..", "..controller..", "..mapper..")
                    .because("实体类应当是对数据库表的纯粹映射，依赖上层会让表结构与业务逻辑互相锁定");

    // ========================================================================
    // 规则五：命名与归属的一致性
    // ========================================================================

    /**
     * Mapper 接口必须位于 mapper 包下。
     *
     * <p>这条规则不像前几条那样关乎架构正确性，它的价值在于<b>可预测性</b>：
     * 看到 {@code PostMapper} 就能直接定位到 {@code forum-forum/.../mapper/PostMapper.java}，
     * 不需要全局搜索。项目越大，这种约定带来的效率差异越明显。
     *
     * <p><b>这条规则不只是「好看」，它和 {@code @MapperScan("com.school.forum.**.mapper")}
     * 编码的是同一个约定。</b>骨架阶段就踩过这个坑：infrastructure 的两个 MQ Mapper
     * 一开始放在 {@code mq/outbox} 和 {@code mq/metrics} 下（与各自的实体同包，
     * 局部看起来更内聚），但那样 {@code @MapperScan} 扫不到它们，
     * 启动时注入直接失败。当时有两条路——放宽扫描模式，或放宽这条规则——
     * 但那是同一个约定的两处破例，最后选择把 Mapper 挪进 {@code mq/mapper}，
     * 两处都不必破例，也和业务模块的布局（{@code entity/Post} 与
     * {@code mapper/PostMapper} 本就分居两包）保持了一致。
     */
    @ArchTest
    static final ArchRule mapper接口必须在mapper包下 =
            classes()
                    .that().haveSimpleNameEndingWith("Mapper")
                    .and().areInterfaces()
                    .should().resideInAPackage("..mapper..")
                    .because("统一的位置约定让代码可以被直接定位，而不必全局搜索");
}
