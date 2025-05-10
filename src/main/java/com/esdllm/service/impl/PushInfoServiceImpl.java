package com.esdllm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.esdllm.bilibiliApi.bilibiliApi.BilibiliClient;
import com.esdllm.bilibiliApi.bilibiliApi.CardInfo;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Live;
import com.esdllm.model.Admin;
import com.esdllm.model.PushInfo;
import com.esdllm.model.respObj.PushInfoResp;
import com.esdllm.service.AdminService;
import com.esdllm.service.PushInfoService;
import com.esdllm.mapper.PushInfoMapper;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.OneBotMedia;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
* @author LiYehe
* @description 针对表【push_info】的数据库操作Service实现
* @createDate 2025-04-22 23:58:21
*/
@Service
public class PushInfoServiceImpl extends ServiceImpl<PushInfoMapper, PushInfo>
    implements PushInfoService{
    private static final ThreadLocal<SimpleDateFormat> SAFE_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));


    @Resource
    PushInfoMapper pushInfoMapper;
    @Resource
    private AdminService adminService;

    @Override
    public PushInfoResp pushAdd(Long roomId, AnyMessageEvent event) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        return pushAdd(roomId, event, 0, 0);
    }

    @Override
    public PushInfoResp pushAdd(Long roomId, AnyMessageEvent event, Integer livePush, Integer dynamicPush) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        PushInfoResp resp = new PushInfoResp();
        if (hasPush(roomId, event.getUserId(), event.getGroupId())) {
            resp.setHas(true);
            return resp;
        }
        resp.setHas(false);

        Long uid = getUid(roomId);
        String userName = getUserName(uid);
        resp.setName(Objects.isNull(userName) ? "未知" : userName);

        int[] pushSettings = parsePushSettings(livePush, dynamicPush);
        int livePushStatus = pushSettings[0];
        int dynamicPushStatus = pushSettings[1];

        PushInfo pushInfo = createPushInfo(roomId, event.getUserId(), event.getGroupId(), livePushStatus, dynamicPushStatus);
        boolean save = this.save(pushInfo);

        if (save) {
            resp.setLivePush(livePushStatus == 0);
            resp.setDynamicPush(dynamicPushStatus == 0);
            return resp;
        }
        return null;
    }

    @Override
    public boolean pushDel(AnyMessageEvent event) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        LambdaQueryWrapper<PushInfo> queryWrapper = new LambdaQueryWrapper<>();
        setWrapper(event.getUserId(), event.getGroupId(), queryWrapper);
        try {
            List<PushInfo> list = this.list(queryWrapper);
            if (list.size() == 1){
                return remove(queryWrapper);
            }else if (list.isEmpty()){
                throw new RuntimeException("没有订阅任何房间");
            }else {
                throw new RuntimeException("请指定房间号");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean pushDel(AnyMessageEvent event, Long roomId) {
        //鉴权
        if (isNotAdmin(event)){
            throw new RuntimeException("您不是管理员");
        }
        LambdaQueryWrapper<PushInfo> queryWrapper = getWrapper(roomId, event.getUserId(), event.getGroupId());
        try {
            int size = pushInfoMapper.selectList(queryWrapper).size();
            if (size == 1){
                return remove(queryWrapper);
            }else {
                throw new RuntimeException("房间号不正确");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直播推送
     * @param bot 机器人对象
     */
    @Override
    public void livePush(Bot bot) {
        List<PushInfo> list = pushInfoMapper.selectList(null);
        // 创建一次对象，避免在循环中重复创建
        Live liveRoom = new Live();
        CardInfo cardInfo = new CardInfo();

        for (PushInfo pushInfo : list) {
            if (!pushInfo.getLivePush().equals(0)){
                continue;
            }
            try {
                Integer currentLiveStatus = liveRoom.getLiveStatus(pushInfo.getRoomId());
                if (!currentLiveStatus.equals(1)&& !currentLiveStatus.equals(0)){
                    if (pushInfo.getLiveStatus().equals(0)){
                        continue;
                    }
                    String message = buildMessage(bot, pushInfo, liveRoom, cardInfo);
                    updatePushInfoStatus(pushInfo);
                    sendMessage(bot, pushInfo, message);
                    continue;
                }
                // 只有当直播状态发生变化时才处理
                if (!Objects.equals(currentLiveStatus, pushInfo.getLiveStatus())) {
                    String sendMsg = buildMessage(bot, pushInfo, liveRoom, cardInfo);

                    // 更新推送信息状态
                    updatePushInfoStatus(pushInfo);

                    // 发送消息
                    sendMessage(bot, pushInfo, sendMsg);
                }
            } catch (Exception e) {
                // 单个推送失败不应影响其他推送
                log.error("处理推送信息时发生异常，房间ID: " + pushInfo.getRoomId(), e);
            }
        }
    }

    @Override
    public void dynamicPush(Bot bot) {
        List<PushInfo> list = pushInfoMapper.selectList(null);
        // 创建一次对象，避免在循环中重复创建
        Live liveRoom = new Live();
        CardInfo cardInfo = new CardInfo();
        Dynamic dynamic = new Dynamic();

        for (PushInfo pushInfo : list) {
            if (!pushInfo.getDynamicPush().equals(0)){
                continue;
            }
            Long uid = liveRoom.getUid(pushInfo.getRoomId());
            try {
                List<Dynamic.DynamicInfo> dynamicInfoList = dynamic.getDynamicInfoList(String.valueOf(uid));
                String username = cardInfo.getUserName(uid);
                for (Dynamic.DynamicInfo dynamicInfo : dynamicInfoList) {
                    if (dynamicInfo.getTime().startsWith("刚刚")){
                        sendMsg(username,dynamic,dynamicInfo,bot,pushInfo);
                        break;
                    }
                }

            }catch (Exception e){
                log.error("处理动态推送信息时发生异常，房间ID: " + pushInfo.getRoomId(), e);
            }
        }

    }

    /**
     *  发送动态消息
     * @param username b站用户名
     * @param dynamic 动态对象
     * @param dynamicInfo 动态信息对象
     * @param bot 机器人对象
     * @param pushInfo 推送信息对象
     * @throws InterruptedException 线程中断异常
     * @throws IOException I/O异常
     */
    private void sendMsg(String username,Dynamic dynamic,Dynamic.DynamicInfo dynamicInfo,Bot bot,PushInfo pushInfo) throws InterruptedException, IOException {
        String sendMsg = "";
        if (dynamicInfo.getBvid() != null){
            sendMsg = MsgUtils.builder().text(username + " 投稿了视频\n")
                    .img(new BilibiliClient().getVideoCoverUrl(dynamicInfo.getBvid()))
                    .text("av"+new BilibiliClient().getVideoAv(dynamicInfo.getBvid())+"\n"+dynamicInfo.getBvid()+
                          "标题："+  new BilibiliClient().getVideoTitle(dynamicInfo.getBvid())+"\n"+
                          "简介："+ new BilibiliClient().getVideoDesc(dynamicInfo.getBvid())+"\n\n"+
                          "https://www.bilibili.com/video/"+dynamicInfo.getBvid()
                    ).build();
        }
        String dynamicId = dynamicInfo.getDynamicId()==null?dynamicInfo.getShareDynamicId()==null?new Date().toString():dynamicInfo.getShareDynamicId():dynamicInfo.getDynamicId();
        File dynamicFile = getDynamicFile(dynamicId);
        if (dynamicInfo.getShareDynamicId()!= null){
            BufferedImage dynamicImg = dynamic.getDynamicImg(dynamicInfo.getShareDynamicId());
            if (Objects.isNull(dynamicImg)){
                return;
            }
            boolean write = ImageIO.write(dynamicImg, "jpg", dynamicFile);
            if (!write){
                return;
            }
            OneBotMedia oneBotMedia = OneBotMedia.builder().file(dynamicFile.getCanonicalPath());
            sendMsg = MsgUtils.builder().text(username + " 转发了动态\n原动态：\n")
                    .img(oneBotMedia).build();
        }

        if (dynamicInfo.getDynamicId()!=null) {
            boolean write = ImageIO.write(dynamic.getDynamicImg(dynamicInfo.getDynamicId()), "jpg", dynamicFile);
            if (!write){
                return;
            }
            OneBotMedia oneBotMedia = OneBotMedia.builder().file(dynamicFile.getCanonicalPath());
            sendMsg = MsgUtils.builder().text(username+"发表了动态")
                    .img(oneBotMedia)
                    .text("https://www.bilibili.com/opus/"+dynamicInfo.getDynamicId())
                    .build();
        }
        sendMessage(bot, pushInfo, sendMsg);
        dynamicFile.deleteOnExit();
    }

    public static File getDynamicFile(String dynamicId) throws IOException {
        String imgPath = "temp/dynamic" + dynamicId + ".jpg";
        File  dynamicFile = new File(imgPath);
        if (!dynamicFile.exists() && !dynamicFile.getParentFile().exists()) {
            if (!dynamicFile.getParentFile().mkdirs()) {
                throw new IOException("无法创建动态文件的父目录: " + dynamicFile.getParent());
            }
        }
        if (!dynamicFile.exists() && !dynamicFile.createNewFile()) {
            throw new IOException("无法创建动态文件: " + dynamicFile.getAbsolutePath());
        }
        return dynamicFile;
    }

    /**
     * @param bot 机器人对象
     * @param live 直播间对象
     * @param pushInfo 推送信息对象
     * @param cardInfo 直播卡片对象
     * 构建推送消息
     */
    private String buildMessage(Bot bot, PushInfo pushInfo, Live live, CardInfo cardInfo) throws IOException {
        List<Long> atListStr = pushInfo.getAtList();
        Long roomId = pushInfo.getRoomId();
        Long uid = live.getUid(roomId);
        String userName = cardInfo.getUserName(uid);

        // 开播消息
        if (pushInfo.getLiveStatus().equals(0)) {
            // 处理开播时间
            processLiveStartTime(pushInfo, live);

            // 根据不同情况构建开播消息
            if (isGroupAdmin(bot, pushInfo.getGroupId()) && pushInfo.getAtAll().equals(1)) {
                return buildAtAllLiveMessage(userName, live, roomId);
            } else if (!atListStr.isEmpty() && !atListStr.get(0).equals(0L)) {
                return buildAtUserLiveMessage(atListStr, userName, live, roomId);
            } else {
                return buildNormalLiveMessage(userName, live, roomId);
            }
        }
        // 下播消息
        else {
            return buildLiveEndMessage(pushInfo, userName);
        }
    }

    /**
     * @param pushInfo 推送信息对象
     * @param liveRoom 直播房间对象
     * 处理直播开始时间
     */
    private void processLiveStartTime(PushInfo pushInfo, Live liveRoom) {
        try {
            SimpleDateFormat formatter = SAFE_DATE_FORMAT.get();
            Date liveTime = formatter.parse(liveRoom.getLiveTime(pushInfo.getRoomId()));
            pushInfo.setLiveTime(liveTime.getTime());
        } catch (ParseException e) {
            log.error("解析开播时间失败", e);
        }
    }

    /**
     * @param userName 用户名
     * @param liveRoom 直播房间对象
     * @param roomId 房间ID
     * 构建@全体成员的开播消息
     */
    private String buildAtAllLiveMessage(String userName, Live liveRoom, Long roomId) throws IOException {
        return MsgUtils.builder().atAll()
                .text(" " + userName + " 开播了" +
                        "\n标题：" + liveRoom.getLiveTitle(roomId) + "\n" +
                        "分区：" + liveRoom.getLiveArea(roomId) + "\n" +
                        "地址：" + liveRoom.getLiveUrl(roomId) + "\n")
                .img(liveRoom.getImageUrl(roomId))
                .build();
    }

    /**
     * 构建@特定用户的开播消息
     */
    private String buildAtUserLiveMessage(List<Long> atListStr, String userName, Live liveRoom, Long roomId) throws IOException {
        StringBuilder msgBuilder = new StringBuilder();
        for (Long aLong : atListStr) {
            msgBuilder.append(MsgUtils.builder().at(aLong).build());
        }

        return msgBuilder + MsgUtils.builder().text(" " +
                        userName + " 开播了" +
                        "\n标题：" + liveRoom.getLiveTitle(roomId) + "\n" +
                        "分区：" + liveRoom.getLiveArea(roomId) + "\n" +
                        "地址：" + liveRoom.getLiveUrl(roomId) + "\n" +
                        "[CQ:image,file=" + liveRoom.getImageUrl(roomId) + "]")
                .build();
    }

    /**
     * 构建普通开播消息
     */
    private String buildNormalLiveMessage(String userName, Live liveRoom, Long roomId) throws IOException {
        return MsgUtils.builder()
                .text(" " + userName + " 开播了" +
                        "\n标题：" + liveRoom.getLiveTitle(roomId) + "\n" +
                        "分区：" + liveRoom.getLiveArea(roomId) + "\n" +
                        "地址：" + liveRoom.getLiveUrl(roomId) + "\n" +
                        "[CQ:image,file=" + liveRoom.getImageUrl(roomId) + "]")
                .build();
    }

    /**
     * 构建下播消息
     */
    private String buildLiveEndMessage(PushInfo pushInfo, String userName) {
        Date now = new Date();
        long between = now.getTime() - pushInfo.getLiveTime();
        long hour = (between / (60 * 60 * 1000));
        long minute = ((between / (60 * 1000)) % 60);
        long second = ((between / 1000) % 60);
        String time = formatLiveTime(hour, minute, second);

        return MsgUtils.builder()
                .text(userName + " 下播了" +
                        "\n直播时长：" + time)
                .build();
    }

    /**
     * 格式化直播时长
     */
    private String formatLiveTime(long hour, long minute, long second) {
        return hour == 0 ?
                minute==0?  second + "秒" : minute + "分钟":
                hour + "时" + minute + "分";
    }

    /**
     * 更新推送信息状态
     */
    private void updatePushInfoStatus(PushInfo pushInfo) {
        // 切换直播状态
        pushInfo.setLiveStatus(pushInfo.getLiveStatus().equals(0) ? 1 : 0);
        pushInfo.setUpdateTime(null);
        pushInfoMapper.updateById(pushInfo);
    }

    /**
     * 发送消息
     */
    private void sendMessage(Bot bot, PushInfo pushInfo, String message) {
        if (Objects.isNull(pushInfo.getGroupId())) {
            bot.sendPrivateMsg(pushInfo.getQqUid(), message, false);
        } else {
            bot.sendGroupMsg(pushInfo.getGroupId(), message, false);
        }
    }

    private Boolean hasPush(Long roomId, Long qqUid, Long groupId) {
        LambdaQueryWrapper<PushInfo> queryWrapper = getWrapper(roomId, qqUid, groupId);
        return this.getOne(queryWrapper) != null;
    }

    private LambdaQueryWrapper<PushInfo> getWrapper(Long roomId, Long qqUid, Long groupId) {
        LambdaQueryWrapper<PushInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PushInfo::getRoomId, roomId);
        setWrapper(qqUid, groupId, queryWrapper);
        return queryWrapper;
    }

    private void setWrapper(Long qqUid, Long groupId, LambdaQueryWrapper<PushInfo> queryWrapper) {
        if (!Objects.isNull(qqUid)) {
            queryWrapper.eq(PushInfo::getQqUid, qqUid);
        }else {
            queryWrapper.isNull(PushInfo::getQqUid);
        }
        if (!Objects.isNull(groupId)) {
            queryWrapper.eq(PushInfo::getGroupId, groupId);
        }else {
            queryWrapper.isNull(PushInfo::getGroupId);
        }
    }

    private Long getUid(Long roomId) {
        return new Live().getUid(roomId);
    }

    private String getUserName(Long uid) {
        try {
            return new CardInfo().getUserName(uid);
        } catch (Exception e) {
            log.error("获取用户名时发生异常", e);
            return null;
        }
    }

    private int[] parsePushSettings(Integer livePush, Integer dynamicPush) {
        int livePushStatus = Objects.isNull(livePush) ? 0 : livePush;
        int dynamicPushStatus = Objects.isNull(dynamicPush) ? 0 : dynamicPush;
        return new int[]{livePushStatus, dynamicPushStatus};
    }

    private PushInfo createPushInfo(Long roomId, Long qqUid, Long groupId, int livePushStatus, int dynamicPushStatus) {
        PushInfo pushInfo = new PushInfo();
        pushInfo.setRoomId(roomId);
        pushInfo.setQqUid(qqUid);
        pushInfo.setGroupId(groupId);
        pushInfo.setLivePush(livePushStatus);
        pushInfo.setDynamicPush(dynamicPushStatus);
        pushInfo.setCreateTime(System.currentTimeMillis());
        pushInfo.setUpdateTime(System.currentTimeMillis());
        return pushInfo;
    }

    private boolean isNotAdmin(AnyMessageEvent event){
        if (Objects.isNull(event.getGroupId())){
            return false;
        }
        if (event.getSender().getRole().equals("owner")|| event.getSender().getRole().equals("admin")){
            return false;
        }
        Long userId = event.getUserId();
        Long groupId = event.getGroupId();
        LambdaQueryWrapper<Admin> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Admin::getQqUid, userId);
        List<Admin> list = adminService.list(queryWrapper);
        for (Admin admin : list) {
            if (Objects.isNull(admin.getGroupId())){
                return false;
            }
            if (admin.getGroupId().equals(groupId)){
                return false;
            }
        }
        return true;
    }

    private boolean isGroupAdmin(Bot bot,Long groupId){
        if (Objects.isNull(groupId)) return true;
        ActionData<GroupMemberInfoResp> memberInfo = bot.getGroupMemberInfo(groupId, bot.getSelfId(), false);
        String role = memberInfo.getData().getRole();

        return !StringUtils.isEmpty(role)&&(role.equals("admin")|| role.equals("owner"));

    }

}


