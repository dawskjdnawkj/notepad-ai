package com.notepad.ai.service;

import com.notepad.common.BusinessException;
import com.notepad.entity.User;
import com.notepad.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局向量索引的管理员判定。
 * <p>
 * 向量索引是**全局单例**：一份 simple-vector-store.json 服务所有用户，
 * 所以备份 / 恢复 / 校验会影响到所有人的检索结果，不是某个用户自己的资源。
 * 这类接口因此收敛成运维操作，只对配置里的账号开放。
 * <p>
 * 默认配置为空 = 谁都不能用（fail-closed）。部署时用
 * {@code NOTEPAD_INDEX_ADMIN_USERNAMES} 指定运维账号（逗号分隔）。
 */
@Slf4j
@Component
public class IndexAdminGuard {

    private static final String REJECT_MESSAGE = "该接口仅限运维账号使用";

    private final UserMapper userMapper;
    private final Set<String> adminUsernames;

    public IndexAdminGuard(UserMapper userMapper,
                           @Value("${notepad.ai.index-admin.usernames:}") String usernames) {
        this.userMapper = userMapper;
        this.adminUsernames = Arrays.stream(usernames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 不是运维账号就抛 403。与其它接口一样，不给调用方区分「没权限」和「不存在」的信息。
     */
    public void requireAdmin(Long userId) {
        if (userId == null || adminUsernames.isEmpty()) {
            log.warn("event=ai.index.admin.rejected userId={} reason={}",
                    userId, adminUsernames.isEmpty() ? "not_configured" : "no_user");
            throw new BusinessException(403, REJECT_MESSAGE);
        }
        User user = userMapper.selectById(userId);
        if (user == null || !adminUsernames.contains(user.getUsername())) {
            log.warn("event=ai.index.admin.rejected userId={} reason=not_admin", userId);
            throw new BusinessException(403, REJECT_MESSAGE);
        }
    }
}
