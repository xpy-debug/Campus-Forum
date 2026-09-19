package com.school.forum.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.school.forum.user.api.UserApi;
import com.school.forum.user.api.UserBrief;
import com.school.forum.user.convert.UserConverter;
import com.school.forum.user.entity.User;
import com.school.forum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户域对外契约的实现。
 *
 * <p>放在 forum-user 的 service 包下（而不是 api 包），是为了让 api 包保持
 * 「只有接口和 DTO、删掉实现也能编译」的纯粹——这条约束由 ArchitectureTest 与
 * 模块依赖方向共同保证，拆服务时才知道它有多值钱。
 */
@Service
@RequiredArgsConstructor
public class UserApiImpl implements UserApi {

    private final UserMapper userMapper;

    @Override
    public UserBrief getBrief(Long userId) {
        if (userId == null) {
            return null;
        }
        return UserConverter.toBrief(userMapper.selectById(userId));
    }

    @Override
    public Map<Long, UserBrief> batchGetBrief(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            // selectList 收到空集合会拼出 `IN ()` 这种非法 SQL，
            // 在这里挡掉，避免调用方为「本页没有帖子」这种正常情况处理异常
            return Map.of();
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getNickname, User::getAvatar, User::getLevel)
                .in(User::getId, userIds));
        return users.stream().collect(Collectors.toMap(
                User::getId,
                UserConverter::toBrief,
                (existing, replacement) -> existing,
                // 用 LinkedHashMap 保持传入顺序，让同一份数据每次渲染结果一致
                LinkedHashMap::new));
    }

    @Override
    public void addPostCount(Long userId, int delta) {
        if (userId == null || delta == 0) {
            return;
        }
        // GREATEST(0, ...) 兜底：post_count 是无符号列，负数增量会让语句直接报错。
        // 计数是冗余数据，由定时对账修正，任何情况下都不该让发帖主流程失败
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("post_count = GREATEST(0, CAST(post_count AS SIGNED) + {0})", delta)
                .eq(User::getId, userId));
    }
}
