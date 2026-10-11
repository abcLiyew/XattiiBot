package com.esdllm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.esdllm.model.PushInfo;
import com.esdllm.model.respObj.PushInfoResp;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;

import java.util.List;

/**
* @author LiYehe
* @description 针对表【push_info】的数据库操作Service
* @createDate 2025-04-22 23:58:21
*/
public interface PushInfoService extends IService<PushInfo> {

    PushInfoResp pushAdd(Long roomId,AnyMessageEvent event);
    PushInfoResp pushAdd(Long roomId,AnyMessageEvent event, Integer livePush, Integer dynamicPush);

    boolean pushDel(AnyMessageEvent event);
    boolean pushDel(AnyMessageEvent event,Long roomId);

    /**
     * 设置「某群 + 某房间」的订阅在主播开播时是否 @全体成员（对应 {@code push_info.at_all}）。
     *
     * <p>作用于该群该房间的<b>全部</b>订阅记录。发送侧一轮内只发一条
     * （见 {@code PushInfoServiceImpl#firstForTarget}），但"选中哪一条"取决于表里的行序，
     * 所以必须让候选都带上这个标记 —— 只改一条的话，会不会 @ 全体就成了偶然。
     *
     * @param groupId 群号（私聊订阅没有"群"，调用方应先在插件层挡掉）
     * @param roomId  直播间房间号
     * @param on      {@code true} = 开播时 @全体；{@code false} = 取消
     * @return {@code true} = 本群确实订阅了该房间（改到了行）；{@code false} = 没有订阅
     */
    boolean setLivePushAtAll(Long groupId, Long roomId, boolean on);

    /**
     * 把某个 QQ 加入 / 移出「某群 + 某房间」开播通知的 @ 名单（对应 {@code push_info.at_list}）。
     *
     * <p>即聊天里的「开播@我 / 取消开播@我」—— 只动这一个 QQ，不碰别人的设置。
     *
     * @return {@code true} = 本群确实订阅了该房间；{@code false} = 没有订阅
     */
    boolean setLivePushAtMe(Long groupId, Long roomId, Long qq, boolean on);

    /**
     * 本群订阅了哪些房间（按房间号去重、升序），附带每个房间当前的 @ 设置摘要。
     *
     * <p>用途是「开播@全体 / 开播@我 这类命令<b>不带房间号</b>」时的两种情况：
     * <ul>
     *   <li>只有<b>一个</b>房间 ⇒ 调用方直接拿它当默认值，用户不用打房间号；</li>
     *   <li>有<b>多个</b> ⇒ 把返回的列表列给用户看，让他补上房间号再发一次。</li>
     * </ul>
     *
     * <p>注意"一个房间"≠"一行记录"：同一房间可能被多个人各加过一条订阅记录
     * （{@code push_info} 按 房间+QQ+群 去重），所以这里必须<b>按 roomId 聚合</b>，
     * 否则会把"本群订了 1 个房间"错报成"订了 3 个"。
     */
    List<RoomAtOption> listRoomOptions(Long groupId);

    /**
     * 「命令不带房间号」时列出来给用户挑的一行：一个房间 + 它当前的 @ 设置摘要。
     *
     * @param roomId 房间号
     * @param uname  主播昵称（订阅时存下的；老数据为空时由 {@code listRoomOptions} 按需补查，仍可能为 {@code null}）
     * @param atAll  该房间是否已开「开播@全体」（该房间任一订阅记录开了即为 true）
     * @param atMe   该房间「开播@我」名单里的 QQ（已去掉占位 {@code 0}，已去重）
     */
    record RoomAtOption(Long roomId, String uname, boolean atAll, List<Long> atMe) {
    }

    void livePush(Bot bot) ;

    void dynamicPush(Bot bot);
}
