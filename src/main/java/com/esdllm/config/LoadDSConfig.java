package com.esdllm.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.mapper.ConfigMapper;
import com.esdllm.model.Admin;
import com.esdllm.model.Config;
import com.esdllm.service.AdminService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加载数据库中的配置
 */
@Slf4j
@Component
public class LoadDSConfig {

    /**
     * 配置键：B 站真实登录 Cookie。
     *
     * <p>为什么要它：动态推送依赖 {@code x/polymer/web-dynamic/v1/feed/space}，该端点匿名过不去
     * —— 实测不带指纹 Cookie 返回 HTTP 412，带上 {@code buvid3/buvid4} 后变成业务码
     * {@code -352}（风控），补客户端指纹参数与换代理出口都无效。只有注入登录 Cookie
     * （浏览器里的 {@code SESSDATA} 等）才能通过，见 bilibili-api 的
     * {@code HttpPolicy#setCookie(String)}。
     *
     * <p>取值：从浏览器开发者工具里复制整串 Cookie 请求头。库里没有该键时，
     * bilibili-api 退回"仅匿名指纹"。
     */
    public static final String KEY_BILI_COOKIE = "biliCookie";

    /**
     * 配置键：B 站请求走的 HTTP 代理，形如 {@code 127.0.0.1:7890}（也接受 {@code http://host:port}）。
     *
     * <p><b>什么时候需要它</b>：Cookie 完全正确、日志里"出站身份"的键名也齐全，但请求<b>持续</b> 412 ——
     * 这说明请求形状没问题，而是<b>出口 IP 被标记</b>了。实测（2026-09-13）：同一枚 Cookie、同一份代码，
     * 从住宅宽带 IP 请求 {@code v1/feed/space} 是 200/code=0，从机房 IP 则是稳定 412；
     * 而同机上 {@code x/frontend/finger/spi} 仍能 200，说明不是整站封 IP，而是该风控在
     * 动态 feed 这条路径上更严。这种情形下换出口 IP 是唯一的软件侧出路。
     *
     * <p>走代理只是把出站流量绕到另一个出口，<b>不改变请求内容</b>；
     * 本库<b>不支持代理认证</b>（只支持 host:port），需要认证的代理请在本机另起一层转发。
     */
    public static final String KEY_BILI_PROXY = "biliProxy";

    /**
     * 配置键：动态推送的<b>数据源偏好</b>，取值 {@code auto}（默认）/ {@code follow} / {@code space}。
     *
     * <p>为什么需要手工指定：实测（2026-09-14）B 站会**按路径封禁**某一台客户端 ——
     * {@code feed/space} 返回 {@code -412 request was banned}，而关注流 {@code feed/all} 正常。
     * 默认的 {@code auto} 会在撞到封禁后自动切到关注流，但**每次重启都要先白撞一次**并多等一轮；
     * 把本键设成 {@code follow} 就能让重启后直接走关注流（一次请求覆盖全部订阅）。
     *
     * <ul>
     *   <li>{@code auto}：先试 {@code feed/space}，被判风控则自动改走关注流（粘性，重启重探）；</li>
     *   <li>{@code follow}：始终走关注流（<b>要求该 B 站账号已关注被订阅的 UP</b>）；</li>
     *   <li>{@code space}：始终按 uid 拉空间动态（未被封的环境用这个）。</li>
     * </ul>
     *
     * <p>改动即时生效（每轮读取），不需要重启。
     */
    public static final String KEY_BILI_DYNAMIC_SOURCE = "biliDynamicSource";

    /**
     * 配置键：动态推送的<b>去重记录</b>（运行状态，不是给人改的参数）。
     *
     * <p><b>为什么必须落库</b>（2026-09-14 真机日志实锤）：去重集合原来只存在内存里，
     * 进程一重启就清空；而触发推送的依据是「这条动态的发布时间在 15 分钟窗口内」，
     * 于是<b>每次重启都会把上一轮已经推过的动态重新推一遍</b>。
     * 实测：动态 {@code dynamicId=1247605274155417607}（发布于 01:06）在 01:07 推过一次，
     * 01:12 重启后 01:13 又推了一次 —— 用户看到的就是"去重没生效"。
     * 而"重启"在开发期极其频繁（每次 `./start.sh` 部署都算一次），所以这不是小概率事件。
     *
     * <p>格式：{@code pid=id,id,id|pid=id,id}（见 {@code PushInfoServiceImpl#serializePushedIds}）。
     * 值会随推送不断被覆盖写回，<b>不要手工编辑</b>；要清空就去掉这一行（代价是重启后重推一轮）。
     */
    public static final String KEY_PUSHED_DYNAMIC_IDS = "pushedDynamicIds";

    @Value(value = "${bot.qq}")
    Long botQQ;
    @Value(value = "${bot.admin}")
    Long admin;

    @Resource
    private ConfigMapper configMapper;

    @Resource
    private AdminService adminService;
    // 使用线程安全的ConcurrentHashMap存储配置
    @Getter
    private final Map<String, String> configMap = new ConcurrentHashMap<>();

    /**
     * 在Bean初始化完成后加载数据库中的配置
     */
    @PostConstruct
    public void load() {
        if (botQQ!=null&&botQQ>0) {
            updateConfig("botQQ",botQQ.toString());
        }
        if (admin!=null&&admin>0) {
            // 只在缺失时写入。原实现是无条件 save() —— 配了 bot.admin 后每次重启都会多一行，
            // 而 bot.admin 正是"机器人所有者"的声明入口（全局管理员，见 BotAdminChecker）。
            Long exists = adminService.count(new LambdaQueryWrapper<Admin>()
                    .eq(Admin::getQqUid, admin)
                    .isNull(Admin::getGroupId));
            if (exists == 0) {
                Admin owner = new Admin();
                owner.setQqUid(admin);
                if (!adminService.save(owner)) {
                    log.error("添加管理员QQ失败");
                } else {
                    log.info("已将 bot.admin={} 登记为全局管理员（机器人所有者）", admin);
                }
            }
        }
        List<Config> configs = configMapper.selectList(null);
        for (Config config : configs) {
            configMap.put(config.getKey(), config.getValue());
        }
        applyBiliHttpSettings();
        log.info("加载数据库中的配置完成，配置数量: {}", configMap.size());
    }

    /**
     * 把 config 表里与 B 站出站请求有关的设置一次性推给 bilibili-api：
     * <b>代理 → Cookie → 抗风控参数</b>，最后统一打一行策略摘要。
     *
     * <p>顺序上先代理后 Cookie：{@link #applyBiliCookie()} 末尾会调用
     * {@link #applyBiliHttpPolicy()}，那里要同时看"有没有 Cookie"和"有没有代理"来决定参数。
     */
    private void applyBiliHttpSettings() {
        applyBiliProxy();
        applyBiliCookie();
        log.info("B 站 HTTP 策略：{}", HttpPolicy.describe());
    }

    /**
     * 把配置表里的代理推给 bilibili-api；未配置则恢复直连。
     *
     * <p>与 Cookie 一样支持即时生效：{@link #afterConfigChanged} 会再次调用本方法。
     */
    private void applyBiliProxy() {
        String raw = configMap.get(KEY_BILI_PROXY);
        if (raw == null || raw.isBlank()) {
            HttpPolicy.clearProxy();
            return;
        }
        String[] target = parseProxyTarget(raw);
        if (target == null) {
            HttpPolicy.clearProxy();
            log.warn("配置 {} 无法解析（{}），已按直连处理；格式应为 host:port，如 127.0.0.1:7890",
                    KEY_BILI_PROXY, raw);
            return;
        }
        HttpPolicy.setProxy(target[0], Integer.parseInt(target[1]));
    }

    /**
     * 解析代理配置：接受 {@code host:port}、{@code http://host:port}（scheme 一律忽略）。
     *
     * <p>刻意<b>不</b>支持 {@code user:pass@host:port} —— 本库的代理参数只有 host/port，
     * 悄悄把账号密码吞掉会让"配了却连不上"变成难查的问题，不如明确拒绝。
     *
     * @param raw 配置值
     * @return {@code [host, port]}；格式非法时返回 {@code null}
     */
    public static String[] parseProxyTarget(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        if (value.contains("@") || value.contains("/")) {
            return null;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return null;
        }
        String host = value.substring(0, colon).trim();
        String portText = value.substring(colon + 1).trim();
        if (host.isEmpty() || !host.matches("[A-Za-z0-9._\\-]{1,253}")) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }

    /**
     * 把配置表里的 B 站 Cookie 推给 bilibili-api 的 HTTP 层。
     *
     * <p>未配置或配成空白时<b>清空</b>，回到只用匿名指纹。
     * 启动时调用一次，之后 {@link #updateConfig}/{@link #deleteConfig} 改到这个键会再次调用，
     * 因此换 Cookie 不用重启机器人。
     */
    private void applyBiliCookie() {
        String cookie = configMap.get(KEY_BILI_COOKIE);
        if (cookie == null || cookie.isBlank()) {
            HttpPolicy.clearCookie();
            applyBiliHttpPolicy();
            log.warn("未配置 {}，bilibili-api 仅使用匿名指纹 —— 动态推送会因 B 站风控（-352）拉不到列表；"
                    + "要开启请发「设置cookie buvid3:... buvid4:... SESSDATA:...」，"
                    + "或在 config 表加一行 key='{}'、value=<浏览器里的完整 Cookie>",
                    KEY_BILI_COOKIE, KEY_BILI_COOKIE);
            return;
        }
        HttpPolicy.setCookie(cookie);
        applyBiliHttpPolicy();
        log.info("已注入 B 站登录 Cookie，键：{}", HttpPolicy.cookieKeys());
    }

    /**
     * 按「有没有注入 Cookie」调整 bilibili-api 的抗风控策略。
     *
     * <p>两个改动都是为了打断<b>自激式风控</b> —— 实测（2026-09-13）B 站的 412 是
     * <b>惩罚窗口</b>行为：短时间内对 {@code api.bilibili.com} 连发几个请求就判 412，
     * 之后一段时间内<b>所有</b>请求继续 412；窗口过期后恢复正常。
     *
     * <ol>
     *   <li><b>有 Cookie 时关闭「风控轮换身份」</b>：Cookie 里的 {@code buvid3/buvid4} 会覆盖
     *       匿名指纹（见 {@code BilibiliHttp.composeCookie}），轮换根本改不了身份，
     *       却要多打一次指纹接口、并<b>立刻重试</b>一次 feed —— 一轮里凭空多出 2 个请求，
     *       正好把惩罚窗口续期。关掉后：命中 412 就立刻返回，一轮只打 1 次。</li>
     *   <li><b>有 Cookie 时把最小请求间隔从 400ms 提到 1200ms</b>：订阅多个 UP 时，
     *       多个 uid 的请求会挤在同一瞬间形成小突发，拉长间隔就不容易踩线。
     *       代价只是每轮慢几秒（轮询间隔 60s，完全够用）。</li>
     * </ol>
     *
     * <p>没有 Cookie 时保持库的默认（轮换开、400ms）—— 匿名场景下轮换是唯一的手段。
     */
    private void applyBiliHttpPolicy() {
        boolean hasCookie = HttpPolicy.hasCookie();
        HttpPolicy.setRotateOnRiskControl(!hasCookie);
        HttpPolicy.setMinRequestIntervalMs(hasCookie ? 1200L : 400L);
    }

    /**
     * 更新数据库中的配置
     * @param key 配置键
     * @param value 配置值
     */
    public void updateConfig(String key, String value) {
        Long count = configMapper.selectCount(new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        if (count == 0) {
            addConfig(key, value);
            return;
        }
        Config config = new Config();
        config.setKey(key);
        config.setValue(value);
        config.setUpdateTime(System.currentTimeMillis());
        configMapper.update(config,new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        configMap.put(key, value);
        afterConfigChanged(key);
        log.info("更新数据库中的配置完成，key: {}, value: {}", key, logValue(key, value));
    }
    /**
     * 删除数据库中的配置
     * @param key 配置键
     */
    public void deleteConfig(String key) {
        configMapper.delete(new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        configMap.remove(key);
        afterConfigChanged(key);
        log.info("删除数据库中的配置完成，key: {}", key);
    }
    /**
     * 添加数据库中的配置
     * @param key 配置键
     * @param value 配置值
     */
    private void addConfig(String key, String value) {
        Config config = new Config();
        config.setKey(key);
        config.setValue(value);
        configMapper.insert(config);
        configMap.put(key, value);
        afterConfigChanged(key);
        log.info("添加数据库中的配置完成，key: {}, value: {}", key, logValue(key, value));
    }

    /**
     * 配置变更后的联动：B 站相关设置需要**即时**推给 bilibili-api
     * （放在这里而不是调用方，保证"无论谁改、改哪条路径"都会生效）。
     */
    private void afterConfigChanged(String key) {
        if (KEY_BILI_COOKIE.equals(key)) {
            applyBiliCookie();
        } else if (KEY_BILI_PROXY.equals(key)) {
            applyBiliProxy();
        }
    }

    /**
     * 日志里回显配置值。<b>Cookie 是凭据，只打长度、绝不打内容</b>。
     *
     * @param key 配置键
     * @param value 配置值
     * @return 可安全写进日志的字符串
     */
    private static String logValue(String key, String value) {
        if (KEY_BILI_COOKIE.equals(key)) {
            return value == null ? "null" : "已设置（长度 " + value.length() + "，值不打印）";
        }
        if (KEY_PUSHED_DYNAMIC_IDS.equals(key)) {
            // 去重记录会随每次推送被写回，内容是一长串 ID：打进日志既没用又会淹掉别的行
            return value == null ? "null" : "已更新（长度 " + value.length() + "，内容不打印）";
        }
        return value;
    }


}
