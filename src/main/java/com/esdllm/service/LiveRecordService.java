package com.esdllm.service;

import com.esdllm.model.LiveRecordFile;
import com.esdllm.model.LiveRecordSub;
import com.mikuac.shiro.core.Bot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * 直播录制（录播）服务。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>录什么</b>：{@link LiveRecordSub}（录播订阅）—— 增删查、开关；</li>
 *   <li><b>录到哪</b>：启动/停止 ffmpeg（真正的进程管理在 {@link LiveRecorder}）；</li>
 *   <li><b>录完怎么办</b>：登记 {@link LiveRecordFile}、按两级水位压缩与淘汰；</li>
 *   <li><b>怎么给出去</b>：{@link #deliver}（本地路径 / HTTP URL / base64 三选一，
 *       见 {@code LoadDSConfig#KEY_BILI_RECORD_DELIVERY_MODE}）。</li>
 * </ul>
 *
 * <p>调度由 {@code botPlugins.LiveRecordPlugins} 驱动：{@link #tick()} 发现开播，
 * {@link #maintain()} 做容量巡检。两者都必须<b>快速返回</b> ——
 * 真正的录制与压缩都在后台线程里跑。
 *
 * <h2>{@code groupId} 这条线怎么理解（每个方法都带着它，先说清楚）</h2>
 * <p>订阅是<b>按群</b>的：群里加的订阅记下群号，私聊加的（只有机器人所有者能做）是
 * {@code null} = 全局订阅。它<b>只影响两件事</b>：
 * <ol>
 *   <li><b>谁能看见</b>：{@link #roomsOfGroup} + {@link #listFilesOfRooms} 决定了
 *       「录播网页」上这个群能看到哪些场次；</li>
 *   <li><b>列表给谁看</b>：{@link #listSubs(Long)} 只列本群的订阅。</li>
 * </ol>
 * <p>它<b>不</b>影响"录几路"：同一房间被两个群订阅就是两行，而 {@link #tick()}
 * 按 {@code room_id} 去重，仍然只录一路（否则同一场直播会占两份磁盘、跑两个 ffmpeg）。
 *
 * @author 饿死的流浪猫
 */
public interface LiveRecordService {

    // ------------------------------------------------------------------ 订阅

    /** <b>全部</b>录播订阅（含已暂停的、含全局的）—— 给机器人所有者的运维视角看。 */
    List<LiveRecordSub> listSubs();

    /**
     * 某个群的录播订阅（含已暂停的）。
     *
     * @param groupId 群号；{@code null} 表示只看全局订阅（私聊里加的那些）
     */
    List<LiveRecordSub> listSubs(Long groupId);

    /**
     * 取订阅。{@code groupId} 参与匹配：{@code null} 匹配"全局订阅"那一行，
     * 有值则只匹配该群自己的那行。
     *
     * @return 没有则返回 {@code null}
     */
    LiveRecordSub findSub(Long roomId, Long groupId);

    /**
     * 加一个录播订阅。会去 B 站取一次 uid 与昵称（失败不阻断，只是昵称空着）。
     *
     * @param groupId 归属群；{@code null} = 全局订阅
     * @return 新增的订阅；<b>本群</b>已经订阅过这个房间时返回已存在的那条
     *         （别的群订过不影响 —— 那是另一行）
     */
    LiveRecordSub addSub(Long roomId, Long groupId) throws IOException;

    /** 开关某个订阅的自动录制（按群定位那一行）。 */
    boolean setSubAutoRecord(Long roomId, Long groupId, boolean on);

    /**
     * 取消订阅（逻辑删除；已录好的文件不动）。
     *
     * <p>⚠️ 只有"这个房间<b>已经没有任何订阅</b>了"才会顺手停掉正在录的那一路 ——
     * 别的群还订着的时候停掉，等于把人家正在录的东西掐了。
     */
    boolean removeSub(Long roomId, Long groupId);

    /**
     * 某个群<b>曾经订阅过</b>的全部房间号 —— 「录播网页」的可见性就是这个集合。
     *
     * <p>⚠️ <b>含已取消的订阅</b>：取消订阅的承诺是"已经录好的文件都留着"，
     * 若这里只算有效订阅，取消之后那些文件会在网页上凭空消失、看起来像被删了。
     *
     * @param groupId 群号；{@code null} 返回空集
     */
    List<Long> roomsOfGroup(Long groupId);

    /**
     * 这个房间是不是<b>还有人在订阅</b>（任意群、任意行）。
     *
     * <p>用来在"加订阅"时给一句实话：这个房间别人已经在录了 —— 同一场只会有一份文件。
     * 也用于"取消订阅时该不该停掉正在录的那一路"。
     */
    boolean isSubscribed(Long roomId);

    // ------------------------------------------------------------------ 调度

    /** 开播检测：对每个开启的订阅查一次直播状态，在播且没在录就启动录制（按房间去重）。 */
    void tick();

    /** 容量巡检：超压缩水位就踢一次压缩，超硬水位就删最早的（保留标记的除外）。 */
    void maintain();

    // ------------------------------------------------------------------ 查询

    /** 分页列出录播（按开始时间倒序，新的在前）。 */
    List<LiveRecordFile> listFiles(int page, int size, Long roomIdFilter);

    /** 录播总条数。 */
    long countFiles(Long roomIdFilter);

    /**
     * 分页列出<b>指定若干房间</b>的录播 —— 网页按群过滤就走这条。
     *
     * @param roomIds {@code null} = <b>不按房间过滤</b>（全局视图，只有管理码能拿到）；
     *                空集合 = 这个群还没订阅过任何房间，直接返回空
     */
    List<LiveRecordFile> listFilesOfRooms(int page, int size, Collection<Long> roomIds);

    /** {@link #listFilesOfRooms} 的总条数，{@code roomIds} 语义同上。 */
    long countFilesOfRooms(Collection<Long> roomIds);

    /** {@link #listFilesOfRooms} 的总占用字节，{@code roomIds} 语义同上。 */
    long usedBytesOfRooms(Collection<Long> roomIds);

    /** 按 id 取录播。 */
    LiveRecordFile findFile(Long fid);

    /**
     * 取一场录播<b>当前在磁盘上的绝对路径</b>（不做任何转换）；文件不在则返回 {@code null}。
     *
     * <p>给"这个还在不在"这类判断用（网页列表要标出文件已被手工删掉的场次）。
     */
    Path fileOf(LiveRecordFile row);

    /**
     * 取一场录播<b>可以直接播的</b>文件：不是 mp4 就先就地转成 mp4
     * （{@code -c copy} 重封装，无损；转完源 flv 会被删掉，与 {@link #deliver} 同一套做法）。
     *
     * <p>为什么要转：录下来的是 flv，浏览器（除装了 MSE 的）放不了。
     *
     * <p>⚠️ 会<b>改磁盘</b>（就地把 flv 换成 mp4 并更新库里的 path/size），
     * 所以同一个文件的并发请求必须串行 —— 本方法内部保证同一时刻只有一个转换在跑。
     *
     * @throws IOException 找不到记录、没录到文件、或转换失败（消息里带原因，可直接回给用户）
     */
    Path playableFile(Long fid) throws IOException;

    /**
     * 给用户看的文件名（{@code 主播-开始时间-fid.mp4}）。
     *
     * <p>放在服务里而不是让每个出口各拼一遍：QQ 发文件和浏览器下载用的是同一个名字，
     * 两处各写一份必然有一天会分叉（而文件名是用户唯一能对上"这是哪一场"的线索）。
     *
     * <p>⚠️ 结果已去掉路径分隔符与控制字符 —— 它要进 {@code Content-Disposition} 头、
     * 也要交给 NapCat 当落盘文件名。
     *
     * @param row  录播记录
     * @param mp4  扩展名用 mp4 还是 flv
     */
    String suggestedFileName(LiveRecordFile row, boolean mp4);

    /** 设置/取消保留标记（保留的既不参与压缩也不参与淘汰）。 */
    boolean setKeep(Long fid, boolean keep);    /** 删除一场录播（连目录一起删）。正在录制的拒绝。 */
    boolean deleteRecord(Long fid);

    /** 当前正在录制的场次。 */
    List<LiveRecordFile> listActive();

    // ------------------------------------------------------------------ 统计

    /** 录播总占用（字节）。 */
    long usedBytes();

    /** 一段人可读的容量与水位摘要。 */
    String summary();

    /** 当前生效的关键参数（总闸 / 水位 / 压缩参数 / 交付方式 / 基址），给命令回显。 */
    String effectiveConfig();

    /**
     * 内置录播文件服务的自述：监听地址、当前基址、候选地址、以及 NapCat 的 WS 地址。
     *
     * <p>它回答的是这个功能最容易出问题的那一问 —— <b>NapCat 到底该用哪个地址来取文件</b>。
     * 云服务器上机器人常常和 NapCat 不在同一网络命名空间（Docker），
     * 于是 {@code 127.0.0.1} 是错的、而正确的那条要人肉去认。
     */
    String serverInfo();

    /** 录播总闸是否开启。 */
    boolean enabled();

    /** 某房间是否正在录制。 */
    boolean isRecording(Long roomId);

    // ------------------------------------------------------------------ 交付

    /**
     * 把一场录播交付给 QQ 目标（群或私聊）。
     *
     * <p>内部会先把 flv 转成 mp4（{@code -c copy} 就地替换），再按配置的交付方式发出。
     *
     * @param bot     机器人
     * @param groupId 群号（与 qqUid 二选一）
     * @param qqUid   私聊 QQ（群号为空时用）
     * @param row     要交付的录播
     * @return 给人看的一行结果（含用了哪种交付方式）
     */
    String deliver(Bot bot, Long groupId, Long qqUid, LiveRecordFile row);

    // ------------------------------------------------------------------ 生命周期

    /** 启动：建目录、把上次进程遗留的 RECORDING 收尾。 */
    void onStartup();

    /** 关停：尽力停止所有 ffmpeg。 */
    void shutdown();
}
