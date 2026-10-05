package com.esdllm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.esdllm.config.LoadDSConfig;
import com.esdllm.mapper.RecordWebTokenMapper;
import com.esdllm.model.RecordWebToken;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 「录播网页」的<b>访问凭证</b>：群令牌 / 全局管理码的生成与校验。
 *
 * <h2>要解决的问题</h2>
 * 网页是给<b>人</b>（浏览器）看的，而浏览器里没有 QQ 身份 —— {@code BotAdminChecker}
 * 那套判定（{@code AnyMessageEvent} + {@code admin} 表）在这里一个字都用不上。
 * 所以换一个模型：<b>先由有身份的人（botadmin）在 QQ 里换一张票，再把票转给群成员</b>。
 * 票就是这里的令牌。
 *
 * <h2>两种令牌，靠 {@code group_id} 区分（不再加 kind 列）</h2>
 * <table border="1">
 *   <tr><th></th><th>群令牌（{@code group_id} 有值）</th><th>全局管理码（{@code group_id} 为空）</th></tr>
 *   <tr><td>谁能拿</td><td>群里的 botadmin（发「录播网页」）</td><td>botadmin（发「录播管理码」）</td></tr>
 *   <tr><td>怎么给</td><td><b>打在链接里</b>，群里回，方便转发给群成员</td>
 *       <td><b>私聊</b>投递 —— 群里回一句等于把它贴在墙上</td></tr>
 *   <tr><td>能看什么</td><td>本群订阅过的房间录出来的东西</td><td>全部（含全局订阅）</td></tr>
 *   <tr><td>能做什么</td><td>只读：浏览 / 在线播放 / 下载</td><td>只读 + <b>改保留、删录播</b></td></tr>
 *   <tr><td>重置</td><td>「录播网页重置」（群内 botadmin）</td><td>「录播管理码重置」（机器人所有者）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>为什么群令牌是"共享密码"而不是"每人的私票"</b>：让群成员逐个领票需要一套
 * 用户体系（账号、绑定、找回），而这条功能的产出只是"一群熟人看同一批录播"。
 * 共享令牌的代价是"链接外泄 = 外人能看"，所以配了两个出口：<b>一键重置</b>（旧链接立刻失效）
 * 与 <b>{@code biliRecordWebEnabled} 总闸</b>（默认关，不合意就整个关掉）。
 *
 * <h2>为什么令牌不是"算出来的"</h2>
 * 不用 {@code HMAC(密钥, 群号)} 这种派生方案：那样"重置某个群"就得换掉全局密钥、
 * 把所有群的链接一起打死。这里直接存 16 字节随机数，重置就是改一行 ——
 * 代价只是多一张表，换来的是能<b>按群</b>处置泄露。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Component
public class RecordWebAuth implements ApplicationListener<WebServerInitializedEvent> {

    /** 管理动作的请求头名：网页上改保留 / 删录播时要带上管理码。 */
    public static final String MANAGE_HEADER = "X-Record-Manage";

    /** 令牌形状：16 字节随机数的十六进制。 */
    private static final int TOKEN_BYTES = 16;
    private static final int TOKEN_HEX_LENGTH = TOKEN_BYTES * 2;

    /** 令牌类型（{@link View#kind()} 的取值） */
    public static final String KIND_GROUP = "group";
    public static final String KIND_ADMIN = "admin";

    /** 令牌解析结果：这个令牌代表谁。 */
    public record View(String kind, Long groupId) {

        /** 是不是全局管理码（能管理、能看全部）。 */
        public boolean admin() {
            return KIND_ADMIN.equals(kind);
        }
    }

    @Resource
    private RecordWebTokenMapper tokenMapper;
    @Resource
    private LoadDSConfig loadDSConfig;
    /** 只为了拿"对外候选地址"来拼网页基址 —— 那边已经有一整套网卡推断逻辑，不再写第二份 */
    @Resource
    private RecordFileServer fileServer;

    private final SecureRandom random = new SecureRandom();

    /** HTTP 服务（Tomcat）实际监听的端口；0 = 还没起来 / 本进程不是 web 应用 */
    private volatile int webPort = 0;

    /** "总闸开着但端口未知"的告警只打一次 */
    private volatile boolean warnedNoPort = false;

    /** "推断出的基址是内网地址"的告警只打一次（每发一次命令就刷一条会很吵） */
    private volatile boolean warnedPrivateBase = false;

    /**
     * "显式配置的基址看着是内网地址"的告警只打一次。
     *
     * <p>与上面那个不同：这一格<b>不拦</b>（内网部署合法，值照用），只是往日志里留个记号，
     * 方便事后查"群里都说链接打不开"。
     */
    private volatile boolean warnedExplicitPrivate = false;

    // ------------------------------------------------------------------ 生命周期

    /**
     * 等到 HTTP 服务真正起来，拿到它<b>实际</b>监听的端口。
     *
     * <p>为什么不用 {@code @Value("${server.port}")}：那读的是<b>配置值</b>。
     * 配 {@code 0}（让系统随机挑一个）时它是 0，而我们要拼进链接里的必须是真端口；
     * 端口被占、或有人用命令行 {@code --server.port=} 压掉时同理（本机验证就是这么跑的）。
     * 这个事件给的才是真的。
     */
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        webPort = event.getWebServer().getPort();
        log.info("录播网页：HTTP 服务监听端口 {}（总闸{}）",
                webPort, loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_RECORD_WEB_ENABLED) ? "已开" : "关闭中");
    }

    // ------------------------------------------------------------------ 总闸

    /**
     * 网页功能是否可用 = <b>总闸打开</b> 且 <b>HTTP 服务在监听</b>。
     *
     * <p>默认关（fail-closed，只有 {@code true/1/on/yes} 才算开），理由与录播总闸同一套：
     * 这是把本机磁盘上的文件<b>暴露给浏览器</b>的功能，谁想要谁自己开。
     */
    public boolean enabled() {
        if (!loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_RECORD_WEB_ENABLED)) {
            return false;
        }
        if (webPort <= 0) {
            if (!warnedNoPort) {
                warnedNoPort = true;
                log.warn("录播网页总闸已开，但拿不到 HTTP 服务的监听端口 ⇒ 暂时不可用"
                        + "（本进程可能不是 web 应用：检查 spring-boot-starter-web / server.port）");
            }
            return false;
        }
        return true;
    }

    /** 实际监听端口；{@code 0} = 未知。 */
    public int port() {
        return webPort;
    }

    // ------------------------------------------------------------------ 令牌解析

    /**
     * 把 URL 里的令牌解析成"这是谁"。
     *
     * <p>先按形状筛（32 位十六进制）再查库：{@code /record/favicon.ico} 这类请求
     * 压根不该在我们这儿引起一次数据库查询 —— 而浏览器的杂项请求是必然发生的。
     *
     * @return 解析结果；形状不对 / 查不到 / 查库出错都返回 {@code null}（调用方一律当"无效链接"）
     */
    public View resolve(String token) {
        String normalized = normalize(token);
        if (normalized == null) {
            return null;
        }
        RecordWebToken row;
        try {
            row = tokenMapper.selectOne(new LambdaQueryWrapper<RecordWebToken>()
                    .eq(RecordWebToken::getToken, normalized)
                    .last("LIMIT 1"));
        } catch (Throwable t) {
            // fail-closed：判不出身份就当没身份，绝不能"查库失败就放行"
            log.warn("校验录播网页令牌时查库失败，按无效链接处理：{}", t.toString());
            return null;
        }
        if (row == null) {
            return null;
        }
        return row.getGroupId() == null
                ? new View(KIND_ADMIN, null)
                : new View(KIND_GROUP, row.getGroupId());
    }

    /** 这个令牌是不是<b>全局管理码</b>（网页上改保留 / 删录播要它）。 */
    public boolean isAdminToken(String token) {
        View view = resolve(token);
        return view != null && view.admin();
    }

    /**
     * 令牌形状检查：必须是 {@value #TOKEN_HEX_LENGTH} 位十六进制。
     *
     * @return 归一化（小写）后的令牌；不合法返回 {@code null}
     */
    private static String normalize(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.length() != TOKEN_HEX_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        return trimmed.toLowerCase();
    }

    // ------------------------------------------------------------------ 取 / 建 / 重置

    /**
     * 取本群的群令牌；<b>没有就建一个</b>（消息里回的那条链接要能直接用，不能让用户先"建令牌"）。
     *
     * @param groupId 群号；{@code null} 返回 {@code null}（没有"全局浏览入口"这种东西）
     */
    public String groupToken(Long groupId) {
        if (groupId == null) {
            return null;
        }
        RecordWebToken row = findByGroup(groupId);
        if (row != null && notBlank(row.getToken())) {
            return row.getToken();
        }
        return insert(groupId);
    }

    /** 重置本群群令牌：旧链接<b>立刻失效</b>（用于链接被转到群外了）。 */
    public String resetGroupToken(Long groupId) {
        if (groupId == null) {
            return null;
        }
        return rewrite(findByGroup(groupId), groupId);
    }

    /**
     * 取全局管理码；没有就建一个。
     *
     * <p>⚠️ 调用方<b>必须私聊投递</b>这个值。
     */
    public String adminToken() {
        RecordWebToken row = findByGroup(null);
        if (row != null && notBlank(row.getToken())) {
            return row.getToken();
        }
        return insert(null);
    }

    /** 重置管理码：所有已发出去的网页管理权限立刻失效。 */
    public String resetAdminToken() {
        return rewrite(findByGroup(null), null);
    }

    /**
     * 按 group_id 取令牌行；{@code groupId == null} 时匹配 {@code group_id IS NULL}（全局那一行）。
     *
     * <p>⚠️ 全局那一格必须用 {@code isNull} 而不是 {@code eq(null)}：SQL 里
     * {@code group_id = null} <b>恒不成立</b>（三值逻辑），写成 {@code eq} 会变成
     * "永远查不到 ⇒ 每次调用都新建一行"，而且每一行都还能被同一个 token 查到，
     * 属于那种"功能看着正常、数据一直在长"的坏法。
     */
    private RecordWebToken findByGroup(Long groupId) {
        LambdaQueryWrapper<RecordWebToken> wrapper = new LambdaQueryWrapper<>();
        if (groupId == null) {
            wrapper.isNull(RecordWebToken::getGroupId);
        } else {
            wrapper.eq(RecordWebToken::getGroupId, groupId);
        }
        // LIMIT 1：理论上每群只有一行，但并发"取或建"确实可能落下重复行；
        // 取一行总比 selectOne 撞到多行直接抛异常好
        return tokenMapper.selectOne(wrapper.orderByAsc(RecordWebToken::getTid).last("LIMIT 1"));
    }

    private String insert(Long groupId) {
        String token = newToken();
        long now = System.currentTimeMillis();
        tokenMapper.insert(new RecordWebToken()
                .setGroupId(groupId)
                .setToken(token)
                .setCreateTime(now)
                .setUpdateTime(now));
        log.info("已生成录播网页令牌（{}）", describeTarget(groupId));
        return token;
    }

    private String rewrite(RecordWebToken row, Long groupId) {
        if (row == null) {
            return insert(groupId);
        }
        String token = newToken();
        tokenMapper.update(null, new LambdaUpdateWrapper<RecordWebToken>()
                .eq(RecordWebToken::getTid, row.getTid())
                .set(RecordWebToken::getToken, token)
                .set(RecordWebToken::getUpdateTime, System.currentTimeMillis()));
        log.info("已重置录播网页令牌（{}）⇒ 旧链接立刻失效", describeTarget(groupId));
        return token;
    }

    private String newToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static String describeTarget(Long groupId) {
        return groupId == null ? "全局管理码" : "群 " + groupId + " 的群令牌";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ------------------------------------------------------------------ 基址

    /**
     * 网页的<b>对外</b>基址（形如 {@code http://rec.example.com} 或 {@code http://1.2.3.4:2233}）。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@code biliRecordWebBaseUrl} 显式配置 —— 只有人知道真实拓扑（域名、反代、端口映射）；</li>
     *   <li>自动推断：{@link RecordFileServer#externalCandidates()} 里<b>第一条公网可达的地址</b>
     *       + 本进程的监听端口。⚠️ 是"扫一遍找公网"，不是"只看第一条" ——
     *       多网卡机器上候选可能长成 {@code [192.168.1.9, 103.43.8.51]}，
     *       只看第一条会把明明能用的公网地址判死（而且会让下面那句"候选都是内网地址"变成假话）。</li>
     * </ol>
     *
     * <p>🔴 <b>为什么"推断不出公网地址"时宁可返回 {@code null} 也不给链接</b>（2026-10-05 定的口径）：
     * 这条链接是发给<b>群成员</b>的，他们多半在公网；而候选筛选只能排掉"按网卡名看得出是网桥"的，
     * 认不出的虚拟网卡（WSL、代理 TUN）照样进候选 —— 这台机器<b>自己判断不了</b>
     * "外面的浏览器到底能不能到"。
     *
     * <p>判断不了就得<b>说出来</b>：没有公网地址就<b>直接不下发</b>，把"配一个公网可达的域名/IP"
     * 交回给唯一的知情人（机器人所有者），而不是发一条点开超时、命令还报"成功"的链接。
     * 早先的做法是"照发 + 附一句可能打不开"，等于把判断成本转嫁给了群成员。
     *
     * <p>显式配置<b>原样信任</b>（只吃末尾斜杠）：那是人按真实拓扑定的，
     * 内网部署（机器人与群成员同一局域网）也合法。但如果它明显是个<b>内网 IPv4</b>，
     * 会打一条 WARN —— 值照用，只在日志里留记号（见 {@link #warnIfExplicitLooksPrivate}）。
     *
     * @return 基址；<b>没有公网可达的地址时返回 {@code null}</b>
     *         （调用方应提示显式配置 —— 见 {@link #baseUrlAdvice()} —— 绝不硬凑一个）
     */
    public String baseUrl() {
        String explicit = loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL, null);
        // 注意 stringOf 已经做过「空白当没配」和 trim，这里不必再判一次空
        if (explicit != null) {
            String trimmed = explicit.endsWith("/")
                    ? explicit.substring(0, explicit.length() - 1) : explicit;
            warnIfExplicitLooksPrivate(trimmed);
            return trimmed;
        }
        if (webPort <= 0) {
            return null;
        }
        List<String> ips = fileServer.externalCandidates();
        for (String ip : ips) {
            if (looksPublic(ip)) {
                return "http://" + ip + ":" + webPort;
            }
        }
        // 走到这里 ⇒ 整张候选表里没有一条是公网可达的（含"压根没有候选"）
        if (!ips.isEmpty() && !warnedPrivateBase) {
            warnedPrivateBase = true;
            log.warn("录播网页：推断出的候选里没有公网可达的地址（{}:{}）⇒ 按口径不下发链接。"
                            + "请在 config 表把 {} 配成公网可达的域名或 IP。全部候选：{}",
                    ips.get(0), webPort, LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL, ips);
        }
        return null;
    }

    /**
     * 基址<b>用不了</b>时给机器人所有者的下一步指引；能正常发链接时返回 {@code null}。
     *
     * <p>与 {@link #baseUrl()} 严格互补：{@code baseUrl() == null} ⇒ 这里必给一段话。
     * 正因如此它取代了早先那个"链接照发、另附一句可能打不开"的 {@code baseUrlCaveat()} ——
     * 口径从"照发 + 附注"改成"内网地址干脆不下发"之后，这段话就不再是附注，
     * 而是<b>本该给的那条回复</b>。
     *
     * <p>只讲"该做什么"，不复述全部现状；候选地址那点排障信息放在末尾，
     * 因为要配这个键的人（机器人所有者）多半不在出事的那台机器跟前。
     */
    public String baseUrlAdvice() {
        if (baseUrl() != null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ <b>链接没法发</b>：这台机器推断出来的地址不是<b>公网可达</b>的，")
                .append("群成员的浏览器点开是超时。\n");
        if (webPort > 0) {
            sb.append("网页服务本身在跑（端口 ").append(webPort).append("），")
                    .append("缺的是一个<b>外面访问得到</b>的地址。\n");
        }
        sb.append("\n解决：让<b>机器人所有者</b>把配置 ")
                .append(LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL)
                .append(" 设成<b>公网可达的域名或 IP</b>");
        if (webPort > 0) {
            sb.append("（域名一般不带端口，由反代转发；用公网 IP 直连时写 http://IP:")
                    .append(webPort).append("）");
        }
        sb.append("，改完<b>即时生效、不用重启</b>，再发一次本命令即可。");
        if (webPort > 0) {
            List<String> ips = fileServer.externalCandidates();
            if (!ips.isEmpty()) {
                sb.append("\n\n当前推断出的候选（没有一条是公网可达的，仅供排障）：");
                for (String ip : ips) {
                    sb.append("\n  http://").append(ip).append(':').append(webPort);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 粗判一个地址是不是<b>公网可路由的 IPv4</b>。
     *
     * <p>方向刻意保守：<b>不是标准四段 IPv4 的一律当"不公网"</b>（域名、IPv6、空值都算）。
     * 判错的代价不对称 —— 把能用的误判成内网，只是不下发链接、让人去配一下；
     * 把内网的误判成公网，就是发出一条点开超时还报"成功"的链接。
     */
    private static boolean looksPublic(String ip) {
        if (isNotIpv4(ip)) {
            return false;
        }
        String[] parts = ip.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        if (a == 10 || a == 127 || a == 0 || a >= 224) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;                       // 链路本地（Windows 拿不到 DHCP 时的自配地址）
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false;                       // 基准测试段，代理 TUN 常占（Clash 默认 198.18.0.0/15）
        }
        return a != 100 || b < 64 || b > 127;                       // CGNAT
    }

    /** 是不是标准四段十进制 IPv4（每段 0–255）。刻意不用 {@code InetAddress}：那会触发 DNS 解析。 */
    private static boolean isNotIpv4(String s) {
        if (s == null) {
            return true;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return true;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return true;
            }
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') {
                    return true;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return true;
            }
        }
        return false;
    }

    /**
     * 显式配置的基址如果是内网 IPv4，往日志里记一笔（<b>值照用，不拦</b>）。
     *
     * <p>为什么不拦：显式配置是<b>人定的</b>，内网部署（机器人与群成员在同一局域网）
     * 完全合法，拦掉就把这条路堵死了。但"配了个 {@code 192.168.x} 却以为外面能访问"
     * 是真实存在的事故形态，而这条链接会发给整个群 —— 日志里留个记号成本极低。
     */
    private void warnIfExplicitLooksPrivate(String base) {
        if (warnedExplicitPrivate) {
            return;
        }
        String host = hostOf(base);
        // 只认"IPv4 字面量"：域名交给 DNS / 反代去解释，这里判不了也不该判
        if (host == null || isNotIpv4(host) || looksPublic(host)) {
            return;
        }
        warnedExplicitPrivate = true;
        log.warn("录播网页：{} 配的是「{}」—— 看着是内网/回环字面量地址，"
                        + "群成员若在公网将打不开。内网部署可忽略本提示；否则请改成公网可达的域名或 IP。",
                LoadDSConfig.KEY_BILI_RECORD_WEB_BASE_URL, base);
    }

    /**
     * 从基址里抠出主机名。
     *
     * <p>只够本用途：剥掉 scheme、路径、userinfo、端口。返回值可能是域名、IPv4 字面量，
     * 或 IPv6（{@code [...]} 形状，这里直接放弃 —— {@link #looksPublic} 只管 IPv4）。
     */
    private static String hostOf(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String s = base.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        if (s.startsWith("[")) {
            return null;                        // IPv6 字面量
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.isBlank() ? null : s;
    }

    /**
     * 拼一条完整的网页链接。
     *
     * @return 链接；<b>没有公网可达的基址时返回 {@code null}</b>
     *         （调用方不要"凑合发一条"，而应转 {@link #baseUrlAdvice()} 的指引）
     */
    public String link(String token) {
        String base = baseUrl();
        return base == null || token == null ? null : base + "/record/" + token;
    }
}
