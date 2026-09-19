package com.school.forum.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.forum.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表数据访问。
 *
 * <p>目前只需要 {@code BaseMapper} 提供的能力（按用户名查、按 ID 批量查、插入、更新），
 * 故没有配套 XML。一旦出现「查活跃用户排行」这类需要手写 SQL 的场景，
 * 再把语句写进 {@code resources/mapper/UserMapper.xml}。
 *
 * <p><b>必须放在 {@code mapper} 包下。</b>这不是审美问题：{@code @MapperScan("com.school.forum.**.mapper")}
 * 只扫这个包，放错位置 Bean 注册不上，启动直接失败。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
