package com.school.forum.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis-Plus 配置。
 */
@Configuration
@EnableTransactionManagement
@MapperScan("com.school.forum.**.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页插件。注意本项目的信息流走的是游标分页（手写 SQL），
        // 用不到它；它服务于后台管理那些确实需要「共 N 条 / 跳页」的列表。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，防止有人传 size=1000000 把数据库拖垮
        pagination.setMaxLimit(500L);
        // 溢出总页数后不回到首页而是返回空结果——回到首页会让调用方
        // 以为「第 999 页居然有数据」，产生难以理解的错觉
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 乐观锁插件，配合实体上的 @Version 字段使用。
        // 本项目主要在秒杀活动的库存字段上用它做并发控制。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 阻断全表更新/删除。这条插件会在检测到没有 WHERE 条件的
        // update/delete 时直接抛异常——属于「宁可上线前报错，也不要上线后删库」的防线。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
