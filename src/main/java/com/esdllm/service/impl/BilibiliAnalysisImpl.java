package com.esdllm.service.impl;

import com.esdllm.bilibiliApi.bilibiliApi.*;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.video.Staff;
import com.esdllm.contant.BiliBiliContant;
import com.esdllm.service.BilibiliAnalysis;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class BilibiliAnalysisImpl implements BilibiliAnalysis {
    @Override
    public void bilibiliAnalysis(AnyMessageEvent event, String url, Bot bot) {
        if (url.contains("b23.tv")){
            ShortChain shortChain = new ShortChain(url);
            shortChainAnalysis(event,shortChain,bot);
            return;
        }
        if(url.contains("live.bilibili.com")){
            onLiveUrl(bot,event,url);
            return;
        }
        if (url.contains("bilibili.com/video")){
            onVideoUrl(bot,event,url);
            return;
        }
        if (url.contains("bilibili.com/opus")){
            onDynamicUrl(bot,event,url);
        }
    }
    private void onDynamicUrl(Bot bot, AnyMessageEvent event, String url) {
        String dynamicIdStr = getId(url);
        BilibiliDynamicResp.Data.Card  card;
        try {
            Dynamic dynamic = new Dynamic();
            card = dynamic.getDynamicDetail(dynamicIdStr);
        } catch (IOException e) {
            log.error(e.getMessage());
            return;
        }
        sendDynamicMsg(event,card,bot);
    }

    private String getId(String url) {
        String[] urlArr = url.split("/");
        int indexOf = urlArr[urlArr.length - 1].indexOf("?");
        if (indexOf<=0){
            indexOf =urlArr[urlArr.length-1].length();
        }
        return urlArr[urlArr.length-1].substring(0, indexOf);
    }

    private void onVideoUrl(Bot bot, AnyMessageEvent event, String url) {
        String[] urlArr = url.split("/");
        String videoIdStr = null;
        for (String urlSplit : urlArr) {
            if (urlSplit.startsWith("BV")||urlSplit.startsWith("av")||urlSplit.startsWith("AV")){
                int indexOf = urlSplit.indexOf("?");
                if (indexOf<=0){
                    indexOf =urlSplit.length();
                }
                videoIdStr = urlSplit.substring(0, indexOf);
                break;
            }
        }
        if (videoIdStr==null){
            return ;
        }
        VideoInfo info;
        BilibiliClient bilibiliClient = new BilibiliClient();
        if (videoIdStr.startsWith("BV")){
            try {
                info= bilibiliClient.getVideoInfo(videoIdStr);
            } catch (IOException e) {
                log.error(e.getMessage());
                return ;
            }
        }else {
            Long aid = Long.parseLong(videoIdStr.substring(2));
            try {
                info= bilibiliClient.getVideoInfo(aid);
            } catch (IOException e) {
                log.error(e.getMessage());
                return ;
            }
        }
        sendVideoMsg(event, info,bot);
    }
    private void onLiveUrl(Bot bot, AnyMessageEvent event, String url){
        String roomIdStr = getId(url);
        Long roomId = Long.parseLong(roomIdStr);
        LiveRoom liveRoom = new Live().getLiveRoom(roomId);
        if (liveRoom==null){
            return ;
        }
         sendLiveMsg(event,liveRoom,bot);
    }

    private void shortChainAnalysis(AnyMessageEvent event, ShortChain shortChain, Bot bot) {
        Integer type = shortChain.getShotChainInfo().getType();
        switch (type) {
            case 0:
                LiveRoom liveRoom = shortChain.getLiveRoom();
                sendLiveMsg(event,liveRoom,bot);
                break;
            case 1:
                VideoInfo info = shortChain.getVideoInfo();
                sendVideoMsg(event,info,bot);
                break;
            case 2:
                BilibiliDynamicResp.Data.Card  card = shortChain.getDynamicCard();
                sendDynamicMsg(event,card,bot);
        }
    }

    private void sendDynamicMsg(AnyMessageEvent event, BilibiliDynamicResp.Data.Card card, Bot bot) {
        Dynamic dynamic = new Dynamic();
        try {
            BufferedImage dynamicImg = dynamic.getDynamicImg(card.getDesc().getDynamic_id_str());
            if (Objects.isNull(dynamicImg)){
                return;
            }
            String base64Image = BiliBiliContant.imgToBase64(dynamicImg);
            String sendMsg = MsgUtils.builder().text(card.getDesc().getUser_profile().getInfo().getUname()+" 的动态：\n")
                    .img("base64://"+base64Image).text("https://www.bilibili.com/opus/"+card.getDesc().getDynamic_id_str())
                    .build();
            bot.sendMsg(event,sendMsg,false);
        }catch (Exception e){
            log.error("获取动态图片失败,动态id:{}",card.getDesc().getDynamic_id_str(),e);
        }
    }

    private void sendVideoMsg(AnyMessageEvent event, VideoInfo info, Bot bot) {
        long view = info.getStat().getView();
        String viewStr = String.valueOf(view);
        if (view > 10000){
            viewStr = String.format("%.2f万", (double) view / 10000);
        }
        String msg = MsgUtils.builder().img(info.getPic()).text(
                "av"+info.getAid()+"\n"+
                        info.getBvid()+"\n"+
                        "标题："+info.getTitle()+"\n"+
                        "简介："+info.getDesc()+"\n"+
                        "上传时间："+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(info.getPubdate()*1000)+"\n"+
                        "播放量："+viewStr+
                        "，弹幕："+info.getStat().getDanmaku()+"\n评论："+info.getStat().getReply() +
                        "，收藏："+info.getStat().getFavorite() +
                        "\n点赞："+info.getStat().getLike()+"，投币："+info.getStat().getCoin()+
                        "\n分享："+info.getStat().getShare()+
                        "\nup主："+info.getOwner().getName()+"\n"+"up主uid："+info.getOwner().getMid()+"\n"+
                        getStaff(info.getStaff())+"\nhttps://www.bilibili.com/video/"+info.getBvid()
        ).build();
        bot.sendMsg(event,msg,false);
    }

    private void sendLiveMsg(AnyMessageEvent event, LiveRoom liveRoom, Bot bot) {
        CardInfo cardInfo = new CardInfo();
        String msg = MsgUtils.builder()
                .text(
                        "房间号："+liveRoom.getRoom_id()+"\n"+
                                "标题："+liveRoom.getTitle()+"\n"+
                                "up主："+cardInfo.getUserName(liveRoom.getUid())+"\n"+
                                "up主uid："+liveRoom.getUid()+"\n"+
                                "观看人数："+liveRoom.getOnline()+"\n"+
                                "直播分区："+liveRoom.getArea_name()+"\n"+
                                "开播状态: "+(liveRoom.getLive_status()==1?"正在直播":liveRoom.getLive_status()==0?"未开播":"轮播中")+"\n"+
                                (liveRoom.getLive_status().equals(1)?("开播时间："+liveRoom.getLive_time()+"\n"):"")+
                                "https://live.bilibili.com/"+liveRoom.getRoom_id()+"\n\n"
                ).img(liveRoom.getUser_cover()).build();
        bot.sendMsg(event,msg,false);
    }
    public String getStaff(List<Staff> staff){
        if (staff==null||staff.isEmpty()){
            return "";
        }
        String str = "合作up主：";
        for (Staff s : staff){
            str = s.getName()+",";
        }
        str = str.substring(0,str.length()-1);
        return str;
    }
}
