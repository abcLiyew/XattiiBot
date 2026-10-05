package com.esdllm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 录播文件表 —— <b>一行 = 一场录制</b>（从开播到结束）。同一主播录 N 次就是 N 行。
 *
 * <p>文件落在 {@code <录播目录>/<roomId>/<fid>/} 这个<b>每场一个的子目录</b>里：
 * 取/删/算大小都以这个目录为单位，所以"分片合并失败留下几个 part 文件"这类事
 * 不会变成孤儿文件（删记录就是删目录）。
 *
 * @TableName live_record_file
 */
@TableName(value = "live_record_file")
@Data
@Accessors(chain = true)
public class LiveRecordFile {

    /** 录播 id（同时是磁盘上那个场次目录的名字） */
    @TableId(type = IdType.AUTO)
    private Long fid;

    /** 关联的订阅 id。<b>可空</b>：订阅被取消后已录好的文件要留着 */
    private Long sid;

    /** 直播间房间号 */
    private Long roomId;

    /** 主播 uid */
    private Long uid;

    /** 主播昵称（录制开始时的快照） */
    private String uname;

    /** 本场直播标题（录制开始时的快照） */
    private String title;

    /**
     * 当前主文件路径，<b>相对录播目录</b>（如 {@code 1024/7/out.flv}）。
     * 录制中 / 失败时为 {@code null}。
     */
    private String path;

    /**
     * 本场占用字节数（= 整个场次目录的大小，含合并前的分片残留）。
     *
     * <p>⚠️ 容量统计与淘汰都以它为准；文件被手工删掉时巡检会发现并修正，
     * 所以这个值允许短暂偏离磁盘实况。
     */
    private Long sizeBytes = 0L;

    /** 时长（毫秒）。以 ffmpeg 实际录到的内容为准，不是"墙钟时间" */
    private Long durationMs = 0L;

    /** 实际拿到的清晰度（{@code current_qn}）。⚠️ 与"请求的 qn"可能不同 —— 会被静默降级 */
    private Integer quality = 0;

    /** 本场开始时间戳 */
    private Long startTime;

    /** 本场结束时间戳（0 = 还没结束） */
    private Long endTime = 0L;

    /**
     * 状态：{@code RECORDING} / {@code DONE} / {@code COMPRESSED} / {@code INTERRUPTED} / {@code FAILED}。
     * 取值含义见 {@code schema/sqlite.sql}。
     */
    private String status = "RECORDING";

    /**
     * 保留标记：1 = <b>不参与自动淘汰，也不参与自动压缩</b>。
     *
     * <p>压缩也跳过是刻意的：压缩 = 降分辨率 + H.265 重编码，是有画质损失的操作，
     * 对用户显式标记为"珍藏"的东西不该做。
     */
    private Integer keep = 0;

    /** 是否已被压缩过（1 = 是，此时文件是 mp4） */
    private Integer compressed = 0;

    /** 创建时间戳 */
    private Long createTime = System.currentTimeMillis();

    /** 更新时间戳 */
    private Long updateTime = System.currentTimeMillis();

    /** 逻辑删除 0-否 1-是 默认0 */
    private Integer isDelete = 0;
}
