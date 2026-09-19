/**
 * 用户域。
 *
 * <p><b>包结构约定（所有业务模块一致，便于互相定位代码）：</b>
 * <pre>
 *   com.school.forum.user
 *     ├── api        【对外契约】其他模块只能依赖这里。放接口和 DTO，不放实现
 *     ├── controller  HTTP 入口。只做参数校验、调用 service、包装 Result
 *     ├── service     业务编排。事务边界在这一层
 *     ├── mapper      数据访问（MyBatis-Plus）
 *     ├── entity      数据库实体，与表一一对应
 *     ├── dto          入参对象（Controller ← 前端）
 *     ├── vo           出参对象（Controller → 前端）
 *     ├── convert      转换器（entity ↔ dto ↔ vo），静态方法，不引入 MapStruct
 *     └── event       本域发出/消费的领域事件
 * </pre>
 *
 * <p><b>为什么要有 {@code api} 这一层：</b>
 * 模块化单体的最大风险是「模块划分只停留在目录上」——A 模块直接注入 B 模块的
 * service、甚至直接查 B 的表。这样到了真要拆微服务的时候，
 * 会发现依赖关系是一张网，根本切不开。强制跨模块调用只能走 {@code api} 包之后，
 * 拆分的动作就退化成「把 api 的实现换成 HTTP/RPC 客户端」，
 * 业务代码一行不用改。
 *
 * <p>这条规则不是靠自觉维持的——{@code forum-boot} 里有 ArchUnit 测试
 * {@code ArchitectureTest} 在编译期强制校验，违反会直接导致构建失败。
 */
package com.school.forum.user;
