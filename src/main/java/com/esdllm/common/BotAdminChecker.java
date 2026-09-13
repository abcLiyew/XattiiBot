package com.esdllm.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.model.Admin;
import com.esdllm.service.AdminService;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 机器人管理权限判定（订阅管理、Cookie 配置等敏感操作共用一份规则）。
 *
 * <p><b>两级权限，按操作的"影响面"选，不要混用</b>：
 * <ul>
 *   <li>{@link #isAdmin(AnyMessageEvent)} —— <b>群内</b>管理权限，只用于影响本群的操作
 *       （如「添加订阅 / 取消订阅」）。放行条件：私聊（沿用既有约定）→ 群 {@code owner}/{@code admin}
 *       → {@code admin} 表白名单（全局记录或本群记录）。</li>
 *   <li>{@link #isBotAdmin(AnyMessageEvent)} —— <b>机器人管理员</b>权限，用于改影响全局的配置
 *       （如 B 站 Cookie）。<b>只认 {@code admin} 表白名单</b>，与"群主/群管理员"这类平台身份
 *       完全脱钩：平台身份只说明"他管这个群"，不代表他有权动整个机器人的凭据。
 *       私聊、群聊一视同仁，都必须过这一关。</li>
 * </ul>
 *
 * <p>抽成独立组件是因为鉴权是<b>安全逻辑</b>：复制两份后很容易只改一处，
 * 出现"订阅拦得住、Cookie 拦不住"这类漏洞。
 */
@Slf4j
@Component
public class BotAdminChecker {

    @Resource
    private AdminService adminService;

    /**
     * 是否具备<b>群内</b>管理员权限（只影响本群的操作）。
     *
     * @param event 收到的消息事件
     * @return {@code true} 表示放行
     */
    public boolean isAdmin(AnyMessageEvent event) {
        // 私聊：沿用既有约定，一律放行
        if (Objects.isNull(event.getGroupId())) {
            return true;
        }
        // 群主 / 群管理员（role 为空时不要 NPE，落到白名单判定）
        String role = event.getSender() == null ? null : event.getSender().getRole();
        if ("owner".equals(role) || "admin".equals(role)) {
            return true;
        }
        return isBotAdmin(event) || isGroupAdmin(event);
    }

    /**
     * 是否具备<b>机器人管理员</b>权限，用于改「影响全局」的配置。
     *
     * <p>判定依据只有一个：该 QQ 是否在 {@code admin} 表里（未逻辑删除）。
     * 记录有两种，都算：
     * <ul>
     *   <li>{@code group_id} 为空 —— 全局管理员（机器人所有者），
     *       由 {@code application.yaml} 的 {@code bot.admin} 在启动时自动写入，也可以手工加；</li>
     *   <li>{@code group_id} 有值 —— 该群的管理员。</li>
     * </ul>
     * 这张表是<b>机器人所有者自己维护的白名单</b>（没有命令能往里加人），
     * 所以表内即受信。
     *
     * <p><b>为什么不认群主/群管理员</b>（{@code sender.role}）：那是 QQ 群里的平台身份，
     * 只代表"他管这个群"。以前私聊"一律放行"，等于任何能给机器人发私信的人
     * 都能改全局 Cookie —— 这个洞必须堵上。
     *
     * @param event 收到的消息事件
     * @return {@code true} 表示放行
     */
    public boolean isBotAdmin(AnyMessageEvent event) {
        Long userId = event.getUserId();
        if (Objects.isNull(userId)) {
            return false;
        }
        LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Admin::getQqUid, userId);
        return adminService.count(queryWrapper) > 0;
    }

    /**
     * 该 QQ 是否为当前群的管理员（{@code admin} 表里 group_id 与当前群一致）。
     */
    private boolean isGroupAdmin(AnyMessageEvent event) {
        Long userId = event.getUserId();
        Long groupId = event.getGroupId();
        if (Objects.isNull(userId) || Objects.isNull(groupId)) {
            return false;
        }
        LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Admin::getQqUid, userId).eq(Admin::getGroupId, groupId);
        return adminService.count(queryWrapper) > 0;
    }
}
