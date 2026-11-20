package com.esdllm.botPlugins;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.common.CmdPrefix;
import com.esdllm.common.SignLevel;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.model.SignInRecords;
import com.esdllm.model.respObj.SignInInfo;
import com.esdllm.service.SignInRecordsService;
import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Order;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.enums.AtEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

@Slf4j
@Shiro
@Component
public class SignInPlugins {

    /**
     * 命令的<b>可选前缀</b>：行首空白 / CQ 码（@机器人 等）/ <b>手打的 @机器人</b>。
     *
     * <p>取 {@link CmdPrefix#PREFIX}，与搜索 / cookie / 录播等命令<b>同一口径</b>：
     * {@code 签到}、{@code [CQ:at,qq=213481590] 签到}、{@code @Hana 签到} 都命中，
     * 而 {@code @张三 签到} 不命中（详见 {@link CmdPrefix}）。
     * 前缀<b>自带 flags</b>，下面各条不用再写 {@code (?is)}。
     *
     * <p>⚠️ 命令词本身仍要求<b>整串匹配</b>（shiro 用 {@code matches()}）：{@code 签到}
     * 后面不能再跟别的字 —— 与改造前一致，本次只放宽"命令词之前"。
     */
    private static final String CMD_PREFIX = CmdPrefix.PREFIX;

    /** `签到`（精确） */
    private static final String CMD_SIGN_IN = "^" + CMD_PREFIX + "签到";
    /** `查询`（精确） */
    private static final String CMD_QUERY = "^" + CMD_PREFIX + "查询";
    /** `今日运势`（精确） */
    private static final String CMD_LUCK = "^" + CMD_PREFIX + "今日运势";

    // 获取配置
    @Resource
    private LoadDSConfig loadDSConfig;
    @Resource
    private SignInRecordsService signInRecordsService;

    private Map<String, String> config;
    @Resource
    private BotContainer botContainer;

    private Long qq;

    @PostConstruct
    public void init() {
        config = loadDSConfig.getConfigMap();
        try {
            qq = Long.parseLong(config.get("botQQ"));
        } catch (Exception e) {
            log.error("qq获取失败");
        }
    }

    @AnyMessageHandler
    @Order(1)
    public void setBotQQ(Bot bot, AnyMessageEvent event) {
        Long botQQ = event.getSelfId();
        if (Objects.equals(botQQ, qq)) {
            return;
        }
        qq = botQQ;
        Long aLong = null;
        try {
            aLong = Long.parseLong(config.get("botQQ"));
        } catch (NumberFormatException e) {
            loadDSConfig.updateConfig("botQQ", botQQ.toString());
            config = loadDSConfig.getConfigMap();
        }
        if (Objects.equals(botQQ, aLong)) {
            return;
        }
        loadDSConfig.updateConfig("botQQ", botQQ.toString());
        config = loadDSConfig.getConfigMap();
        log.info("botQQ更新成功");
    }

    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_SIGN_IN, at = AtEnum.BOTH)
    public void signIn(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();

        SignInInfo sign = signInRecordsService.isSign(qqUid, groupId);
        if (Objects.isNull(sign)) {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 签到失败，请稍后再试").build(), false);
            return;
        }
        if (sign.getIsSign()) {
            Double distanceNext = sign.distanceNext();
            BigDecimal distanceNextBigDecimal = new BigDecimal(distanceNext);
            String msg = MsgUtils.builder().at(qqUid).text(" 签到成功\n" +
                            "uid:" + sign.getUid() + "\n" +
                            "好感度+" + sign.getAddEmpirical() + "\n" +
                            "当前好感度：" + sign.getEmpirical() + "\n" +
                            "好感度等级：" + sign.getSignLevel().getOpinion() + "\n" +
                            "对你的态度：" + sign.getSignLevel().getAttitude() + "\n" +
                            "距离下一级：" + (sign.getSignLevel().isMax() ? "已满级"
                                    : distanceNextBigDecimal.setScale(2, RoundingMode.HALF_UP).doubleValue()))
                    .build();
            bot.sendMsg(event, msg, false);
        } else {
            bot.sendMsg(event, MsgUtils.builder().at(qqUid).text(" 今日已签到").build(), false);
        }
    }

    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_QUERY, at = AtEnum.BOTH)
    public void query(Bot bot, AnyMessageEvent event) {
        Long groupId = event.getGroupId();
        Long qqUid = event.getUserId();
        LambdaQueryWrapper<SignInRecords> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SignInRecords::getQqUid, qqUid);
        if (!Objects.isNull(groupId)) {
            queryWrapper.eq(SignInRecords::getGroupId, groupId);
        }
        SignInRecords serviceOne = signInRecordsService.getOne(queryWrapper);
        SignLevel signLevel = SignInInfo.buildSignLevel(serviceOne.getEmpirical());
        double distanceNext = BigDecimal.valueOf(signLevel.getEmpirical() - serviceOne.getEmpirical()).setScale(2, RoundingMode.HALF_UP).doubleValue();
        String msg = MsgUtils.builder().at(qqUid).text(" \nuid:" + serviceOne.getSid() + "\n" +
                        "好感度：" + serviceOne.getEmpirical() + "\n" +
                        "好感度等级：" + signLevel.getOpinion() + "\n" +
                        "对你的态度：" + signLevel.getAttitude() + "\n" +
                        // 满级时不再算"差值"：那是拿 1000 的阈值减出来的假数字
                        "距离下一级：" + (signLevel.isMax() ? "已满级" : distanceNext))
                .build();
        bot.sendMsg(event, msg, false);
    }

    @AnyMessageHandler
    @MessageHandlerFilter(cmd = CMD_LUCK, at = AtEnum.BOTH)
    public void todayLuck(Bot bot, AnyMessageEvent event) {
        Long groupId = Objects.isNull(event.getGroupId()) ? 0L : event.getGroupId();
        Long qqUid = event.getUserId();
        long avg = (groupId + qqUid) / 2;
        LocalDate today = LocalDate.now();
        LocalDateTime todayMidnight = today.atTime(12, 0, 59);
        long timeStamp = todayMidnight.toInstant(java.time.ZoneOffset.of("+8")).toEpochMilli();
        long seed = avg + timeStamp;
        Random random = new Random(seed);
        int finances = random.nextInt(100);
        int peachBlossomLuck = random.nextInt(100);
        int careerLuck = random.nextInt(100);
        String fortuneDesc = getFortuneDesc(finances, peachBlossomLuck, careerLuck);
        String sendMsg = MsgUtils.builder().at(event.getUserId()).text(" 猫猫测运中╰(*°▽°*)╯\n...您今天的运势为：" +
                "\n财运：" + finances + "\n桃花运：" + peachBlossomLuck + "\n事业运：" + careerLuck + "\n点评："
                + fortuneDesc
        ).build();
        bot.sendMsg(event, sendMsg, false);
    }

    private String getFortuneDesc(int fortune, int fortune1, int fortune2) {
        String finances;
        String peachBlossomLuck;
        String careerLuck;
        // 财运
        finances = switch (fortune / 10) {
            case 0, 1 -> "财运平平，小心被人骗~";
            case 2, 3 -> "财运一般，需要注意~";
            case 4, 5 -> "财运不错";
            case 6, 7 -> "财运很好";
            default -> "财运超好";
        };
        // 桃花运
        peachBlossomLuck = switch (fortune1 / 10) {
            case 0, 1 -> "桃花运平平";
            case 2, 3 -> "桃花运一般";
            case 4, 5 -> "桃花运不错";
            case 6, 7 -> "桃花运很好";
            default -> "桃花盛开";
        };
        // 事业运
        careerLuck = switch (fortune2 / 10) {
            case 0, 1 -> "事业运平平，小心被背刺~";
            case 2, 3 -> "事业运一般，需要注意~";
            case 4, 5 -> "事业运不错";
            case 6, 7 -> "事业顺利";
            default -> "事业成功";
        };
        String fortuneDesc = getDesc(fortune, fortune1, fortune2);

        return fortuneDesc + "，" + finances + "，" + peachBlossomLuck + "，" + careerLuck;

    }

    private static String getDesc(int fortune, int fortune1, int fortune2) {
        int fortuneDescNum = (fortune + fortune1 + fortune2) / 3;
        return switch (fortuneDescNum / 10) {
            case 0 -> "凶";
            case 1 -> "较凶";
            case 2 -> "凶带微吉";
            case 3 -> "凶带吉";
            case 4 -> "吉带凶";
            case 5 -> "吉带微凶";
            case 6 -> "较吉";
            case 7 -> "吉";
            case 8 -> "大吉";
            default -> "超大吉";
        };
    }
   /* @Scheduled(cron = "0 10 12 * * ?")
    public void noonMessage(){
        Bot bot = botContainer.robots.get(qq);
        String sendMsg = MsgUtils.builder().at(2534684800L)
                .text("到中午了，今天也要好好吃饭哦，吃的饱饱的睡午觉才会香香的")
                .face(319).text("\n\n来自小五的提醒").build();
        bot.sendGroupMsg(985903541L,sendMsg,false);
    }*/
/*    @Scheduled(cron = "0 0 18 * * ?")
    public void afternoonMessage(){
        Bot bot = botContainer.robots.get(qq);
        String sendMsg = MsgUtils.builder().at(2534684800L)
                .text("下午六点了，时间过好快啊，快快去吃饭，晚上的直播别迟到了（虽然劳模小雨绒从不迟到）")
                .face(318).text("\n\n来自小五的提醒").build();
        bot.sendGroupMsg(985903541L,sendMsg,false);
    }
*/
}