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
 * 机器人管理权限判定（订阅管理、Cookie 配置、扫码登录等敏感操作共用一份规则）。
 *
 * <p><b>三档权限，按操作的"影响面"选，不要混用</b>（严格程度递增，同一个人可以同时具备多档，
 * 每个操作只挑<b>最窄够用</b>的那一档）：
 * <ul>
 *   <li>{@link #isAdmin(AnyMessageEvent)} —— <b>群内</b>权限，只用于影响本群的操作
 *       （如「添加订阅 / 取消订阅」）。放行条件：私聊（沿用既有约定）→ 群 {@code owner}/{@code admin}
 *       → {@code admin} 表白名单（全局记录或本群记录）。</li>
 *   <li>{@link #isBotAdmin(AnyMessageEvent)} —— <b>机器人管理员</b>权限，用于改影响全局的
 *       <b>配置</b>（B 站 Cookie、出口代理）。<b>只认 {@code admin} 表白名单</b>，与"群主/群管理员"
 *       这类平台身份完全脱钩：平台身份只说明"他管这个群"，不代表他有权动整个机器人的配置。
 *       私聊、群聊一视同仁，都必须过这一关。</li>
 *   <li>{@link #isBotOwner(AnyMessageEvent)} —— <b>所有者</b>权限，用于影响全局<b>身份</b>的操作
 *       （扫码登录）。比 {@link #isBotAdmin} 更窄，只认 {@code group_id} 为空的那一条记录。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么"配 Cookie"用 {@code isBotAdmin}、"扫码登录"用 {@code isBotOwner} —— 这是
 * 两套口径，不是同一条口径的松紧两档，不要"顺手统一"</b>：
 * <ul>
 *   <li><b>配置</b>（{@code 设置cookie} / {@code 设置代理}）：管理员是拿一份<b>他自己已经持有</b>的
 *       凭据/出口交给机器人，属于运维动作 —— 授权等级 = 机器人管理员。</li>
 *   <li><b>登录</b>（{@code 登录}）：二维码是一次<b>授权转移</b> —— 谁扫到，机器人就以<b>谁</b>的账号
 *       出站。动作发起方与最终受益方可以不是同一个人，所以只有所有者能发起。</li>
 * </ul>
 * 两者影响面不同、可追责性不同，因此判定不同。改动其中任何一档前请先确认另一档的语义。
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
     * 是否为<b>机器人所有者</b>——三档权限里最窄的一档。
     *
     * <p>判定：{@code admin} 表里存在一条 {@code qq_uid} 匹配、且 {@code group_id} <b>为空</b>
     * 的记录。这个形状正是 {@code config/LoadDSConfig} 启动时按 {@code application.yaml}
     * 的 {@code bot.admin} 写入的那条（它自己也用 {@code isNull(getGroupId)} 判重），
     * 所以"所有者"= 配置里那一个 QQ。
     *
     * <p><b>为什么不能拿 {@link #isBotAdmin} 顶替</b>：{@code isBotAdmin} 只按 {@code qq_uid} 命中，
     * 而 {@code admin} 表里还存在「按群授权」的记录（{@code group_id} 有值）——它们只该拿到
     * <b>群内</b>权限。若用 {@code isBotAdmin} 放行"扫码登录"这类操作，等于把"他在某个群当管理员"
     * 放大成"他能夺取机器人的全局 B 站身份"：<b>越想收窄授权，实际越放大权限</b>。
     *
     * <p>判据很朴素：<b>越敏感的操作，用越窄的判定</b>。夺取全局凭据属于顶格敏感，只给所有者。
     *
     * <p><b>fail-closed</b>：查库异常一律按"不是所有者"处理，绝不在判定失败时放行。
     *
     * @param event 收到的消息事件
     * @return {@code true} 表示确实是所有者
     */
    public boolean isBotOwner(AnyMessageEvent event) {
        Long userId = event.getUserId();
        if (Objects.isNull(userId)) {
            return false;
        }
        try {
            LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Admin::getQqUid, userId).isNull(Admin::getGroupId);
            return adminService.count(queryWrapper) > 0;
        } catch (Exception e) {
            // 安全判定不能 fail-open：出任何岔子都当作"不是所有者"
            log.warn("判定机器人所有者失败，按拒绝处理：userId={}", userId, e);
            return false;
        }
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
