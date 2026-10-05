package com.esdllm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 录播网页的访问令牌 —— <b>一行 = 一个令牌</b>。
 *
 * <p>为什么需要它：浏览器里没有 QQ 身份，判定不了"打开这个页面的人是不是本群成员"。
 * 所以用<b>群级共享令牌</b>：群里的 botadmin 发一次「录播网页」命令拿到链接，转给群成员，
 * 链接本身即凭证（与"发个网盘链接"同一模型，只是有时效之外的额外好处：可一键重置）。
 *
 * <p><b>{@link #groupId} 的两种含义</b>（靠它区分两种令牌，刻意不再加 kind 列）：
 * <ul>
 *   <li><b>有值</b> —— 群令牌。只能看这个群订阅过的房间，给群成员用；</li>
 *   <li><b>为空</b> —— 全局管理码。可看全部（含全局订阅），并能在网页上改保留 / 删录播。
 *       <b>不打进链接里</b>，只由「录播管理码」命令私聊投递 —— 群里回一句等于贴墙上。</li>
 * </ul>
 *
 * <p>⚠️ {@link #token} 是 16 字节安全随机数的十六进制，<b>不是</b>可推导的哈希：
 * "重置"就是把这行的 token 改成新值（旧链接立刻失效），不需要清理历史行，
 * 也因此不需要"过期时间"这一列。
 *
 * @TableName record_web_token
 */
@TableName(value = "record_web_token")
@Data
@Accessors(chain = true)
public class RecordWebToken {

    /** 令牌 id */
    @TableId(type = IdType.AUTO)
    private Long tid;

    /** QQ 群号；{@code null} = 全局管理码（全库只应有一行这样的记录） */
    private Long groupId;

    /** 令牌本身（32 位十六进制）。⚠️ 是凭据，除私聊投递外不要打日志、不要回群里 */
    private String token;

    /** 创建时间戳 */
    private Long createTime = System.currentTimeMillis();

    /** 更新时间戳（重置令牌就是改它和 token） */
    private Long updateTime = System.currentTimeMillis();

    /** 逻辑删除 0-否 1-是 默认0 */
    private Integer isDelete = 0;
}
