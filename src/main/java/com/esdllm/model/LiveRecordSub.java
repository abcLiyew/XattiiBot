package com.esdllm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 录播订阅表 —— <b>一行 = 一个要自动录制的主播</b>（长期存在，与"录了几场"无关）。
 *
 * <p>它和 {@link LiveRecordFile} 是一对：本表回答"盯哪些房间"，那张表回答"录出了什么"。
 * 为什么不合成一张表，见 {@code schema/sqlite.sql} 里录播段的说明。
 *
 * @TableName live_record_sub
 */
@TableName(value = "live_record_sub")
@Data
@Accessors(chain = true)
public class LiveRecordSub {

    /** 订阅 id */
    @TableId(type = IdType.AUTO)
    private Long sid;

    /** 直播间房间号（B 站页面上 {@code live.bilibili.com/1024} 里的 1024） */
    private Long roomId;

    /**
     * 这条订阅是<b>哪个群</b>要的（QQ 群号）。{@code null} = 全局订阅（私聊里加的）。
     *
     * <p>它只决定<b>可见性</b>（谁能在「录播网页」上看到这个房间录出来的东西），
     * <b>不</b>决定录几路 —— 同一房间被多个群订阅时是多行、录制仍只一路，
     * 见 {@code schema/sqlite.sql} 里 live_record_sub 的说明。
     */
    private Long groupId;

    /** 主播 uid（加订阅时顺手取回来存下，省掉之后每次都要问一次接口） */
    private Long uid;

    /** 主播昵称（同上，只用于展示，可能过期） */
    private String uname;

    /**
     * 是否参与自动录制：1 = 录，0 = 暂停（默认 1）。
     *
     * <p>刻意保留"暂停"而不是直接删行：主播停播一段时间再回来是常态，
     * 删了就得重新加，历史记录里的关联也会断。
     */
    private Integer autoRecord = 1;

    /**
     * 期望清晰度 qn（实测 {@code 10000} = 原画）。留 null / ≤0 一律按原画处理。
     *
     * <p>⚠️ 直播"不消耗清晰度权限"（库注释实测），但这个值仍然可能被服务端静默降级 ——
     * 实际拿到什么以录制时读到的 {@code current_qn} 为准，会记在录播文件行上。
     */
    private Integer quality = 10000;

    /** 创建时间戳 */
    private Long createTime = System.currentTimeMillis();

    /** 更新时间戳 */
    private Long updateTime = System.currentTimeMillis();

    /** 逻辑删除 0-否 1-是 默认0（MyBatis-Plus 全局逻辑删除，查询会自动带上 is_delete=0） */
    private Integer isDelete = 0;
}
