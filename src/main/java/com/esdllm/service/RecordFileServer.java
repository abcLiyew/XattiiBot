package com.esdllm.service;

import com.esdllm.common.HttpFileSupport;
import com.esdllm.config.LoadDSConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>录播文件的内置只读 HTTP 服务</b> —— 解决"NapCat 和机器人不在一台机器上"的问题。
 *
 * <h2>它为什么必须存在</h2>
 *
 * OneBot 的 {@code upload_group_file(groupId, file, name)} 里那个 {@code file}
 * 是<b>NapCat 自己去打开的东西</b>，不是我们打开它。所以：
 * <ul>
 *   <li>NapCat 与机器人在同一台机器/同一份挂载里 → 传本地路径即可；</li>
 *   <li>NapCat 在 Docker 容器里（<b>本项目的线上就是</b>，2026-10-04 实测：
 *       {@code docker0=172.17.0.1}、napcat 容器 {@code 172.17.0.2} 在默认 bridge 上）
 *       → <b>容器里根本看不到宿主机的 {@code ./record/}</b>，传本地路径必失败；</li>
 *   <li>NapCat 在另一台服务器上 → 同理必失败。</li>
 * </ul>
 * 后两种情况下唯一可行的办法是<b>让 NapCat 自己去下载</b>：我们给一个 URL，
 * 它把文件拉进自己的数据目录再发出去。这个类就是那个 URL 的提供方。
 *
 * <h2>为什么用 JDK 自带的 HttpServer，而不是把这个端点挂进 Spring MVC</h2>
 * <p>本工程<b>没有</b>显式声明 {@code spring-boot-starter-web}（{@code Main} 还排除了数据源自动配置），
 * 这个端点当初就是"为了它单独引一套 MVC 不值当"才用 {@code com.sun.net.httpserver} 写的：
 * 它是 JDK 内建模块，零依赖、行为可控，只读文件这点事正好够用。
 *
 * <p>⚠️ <b>后来为什么还是有了 Spring MVC</b>：{@code RecordWebController}（录播网页预览）
 * 需要一整个能出 HTML、出 JSON、被浏览器直接访问的东西，那才是 MVC 的活儿 ——
 * 而 MVC 其实一直都在类路径上（{@code spring-boot-starter-web} 的各个 jar 由
 * {@code com.mikuac:shiro} 传递带来，启动日志里的 {@code ConfigServletWebServerApplicationContext}
 * 与 {@code Tomcat started on port ...} 就是证据），所以并没有"新增依赖"。
 *
 * <p>即便如此，<b>这个类仍然留在 JDK 的 HttpServer 上</b>，原因是它与网页那条路的
 * 威胁模型和可达性都不一样：<b>它是给 NapCat 用的</b>（基址可能是 docker 网桥、只需容器可达），
 * 而网页要给群成员的浏览器用（必须是对外地址）。绑在同一套地址推断上一定有一个是错的。
 * 两者共用的是 {@link HttpFileSupport} 里那点 Range 逻辑，不是监听本身。
 *
 * <h2>安全</h2>
 * 这是个"谁连上都能拉走一整场录播"的端点，所以按下面几条收紧：
 * <ul>
 *   <li><b>只能取到已登记的 token</b>：路径里的 token 是 128 位随机数，
 *       映射到<b>一个由我们自己给出的绝对路径</b> —— 客户端无法构造出任何路径，天然没有目录穿越；</li>
 *   <li><b>短时效 + 次数上限</b>：默认 15 分钟、最多 5 次完整下载。
 *       过了就 404 —— 一个"发出去就不管"的下载链接等于永久公开；</li>
 *   <li><b>只认 GET / HEAD</b>，其它方法一律 405；</li>
 *   <li>服务只在<b>有待下载文件时</b>才可能被访问到内容，平时整张表是空的。</li>
 * </ul>
 * ⚠️ 仍然建议在防火墙上把这个端口只放给 NapCat 所在网段（或把
 * {@code biliRecordServeBind} 绑到 docker 网桥地址），别暴露到公网。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Component
public class RecordFileServer {

    /** URL 前缀。改它要同步改 {@link #baseUrl()} 的拼接 */
    private static final String PREFIX = "/rec/";

    @Resource
    private LoadDSConfig loadDSConfig;

    /**
     * NapCat 的 WS 地址，用来推断"哪张网卡是它到我们的必经之路"。
     *
     * <p>取不到（比如反向 WS 模式下这个键是空的）也不影响启动，只是少了一条推断依据。
     */
    @Value("${shiro.ws.client.url:}")
    private String napCatWsUrl;

    private final SecureRandom random = new SecureRandom();

    /** token → 待下载文件。**只有被登记过的才有**，这是安全模型的地基 */
    private final Map<String, Entry> published = new ConcurrentHashMap<>();

    private volatile HttpServer server;

    /** 服务实际监听的端口（配 0 时由系统分配，这里记下真实值） */
    private volatile int actualPort = 0;

    private volatile String bindAddress = "0.0.0.0";

    private ExecutorService executor;

    /**
     * 一次已发布的下载。
     *
     * @param file      绝对路径（由我们提供，客户端无从构造）
     * @param name      展示用的文件名（不影响路径）
     * @param expireAt  过期时刻（毫秒）
     * @param remaining 剩余可下载次数
     */
    private record Entry(Path file, String name, long expireAt, AtomicInteger remaining) {
    }

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    public void start() {
        int port = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_SERVE_PORT,
                LoadDSConfig.DEFAULT_RECORD_SERVE_PORT);
        String bind = loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_SERVE_BIND,
                LoadDSConfig.DEFAULT_RECORD_SERVE_BIND);
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (Exception e) {
            // 端口被占 / 地址不可绑：退到随机端口而不是让整个机器人起不来 ——
            // 录播交付是可选能力，不该有"启动失败"这种代价
            try {
                server = HttpServer.create(new InetSocketAddress(bind, 0), 0);
                log.warn("录播文件服务无法绑定 {}:{}（{}），已退到随机端口。"
                        + "若 NapCat 在容器/别的机器上，请用 {} 显式指定一个可达基址",
                        bind, port, e, LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL);
            } catch (Exception fatal) {
                log.error("录播文件服务启动失败，录制仍可用，但「下载」只能走本地路径模式：{}", fatal.toString());
                return;
            }
        }
        bindAddress = bind;
        actualPort = server.getAddress().getPort();
        // 守护线程池：2 个并发下载足够（一个群里同时取两份录播都算高频了），
        // 且用 daemon 线程避免它拖住 JVM 退出
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "record-file-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/rec", this::handle);
        server.start();

        log.info("录播文件服务已启动：监听 {}:{}（下载链接基址见下）", bind, actualPort);
        logCandidates();
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("录播文件服务已停止");
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 发布

    /**
     * 发布一个文件供下载，返回它的<b>完整 URL</b>。
     *
     * @param file 要发布的文件（绝对路径）
     * @param name 展示名（NapCat 那边看到的文件名）
     * @return 可访问的 URL；服务没起来 / 没有任何可达基址时返回 {@code null}
     */
    public String publish(Path file, String name) {
        if (server == null || actualPort <= 0) {
            return null;
        }
        String base = baseUrl();
        if (base == null) {
            log.warn("录播文件服务已启动，但推断不出 NapCat 能访问到的基址 —— "
                    + "请配置 {}（例如 http://172.17.0.1:{}），否则下载只能走本地路径模式",
                    LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL, actualPort);
            return null;
        }
        int ttlMinutes = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_LINK_TTL_MINUTES,
                LoadDSConfig.DEFAULT_RECORD_LINK_TTL_MINUTES);
        int maxUses = loadDSConfig.intOf(LoadDSConfig.KEY_BILI_RECORD_LINK_MAX_USES,
                LoadDSConfig.DEFAULT_RECORD_LINK_MAX_USES);

        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);

        long expireAt = System.currentTimeMillis() + Duration.ofMinutes(Math.max(1, ttlMinutes)).toMillis();
        published.put(token, new Entry(file.toAbsolutePath().normalize(), name, expireAt,
                new AtomicInteger(Math.max(1, maxUses))));
        purgeExpired();

        String url = base + PREFIX + token;
        log.info("已发布录播下载：{}（{}，{} 分钟内有效、最多下载 {} 次）", url, name, ttlMinutes, maxUses);
        return url;
    }

    /** 清掉过期的登记，避免这张表在长时间运行后无限增长。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        published.entrySet().removeIf(e -> e.getValue().expireAt() < now
                || e.getValue().remaining().get() <= 0);
    }

    // ------------------------------------------------------------------ 基址推断

    /**
     * 推断 NapCat 能访问到的下载基址。
     *
     * <p>优先级（<b>顺序就是"可信度"</b>，别随手调换）：
     * <ol>
     *   <li>{@code biliRecordPublicBaseUrl} 显式配置 —— 永远最优先，因为只有人知道真实拓扑；</li>
     *   <li><b>docker 网桥地址</b>（{@code docker0} / {@code br-*}，实测线上是 {@code 172.17.0.1}）
     *       —— 当 NapCat 在同一台机器上跑容器时，这就是它到宿主机的必经之路；</li>
     *   <li>去往 WS 对端的<b>出口网卡地址</b>（{@code eth0} 之类）—— 当 NapCat 在别的机器上时；</li>
     *   <li>{@code 127.0.0.1} —— 只在 NapCat 与机器人同一个网络命名空间时才有意义，兜底用。</li>
     * </ol>
     *
     * <p>⚠️ <b>最容易错的一格是第 4 位被当成第 1 位</b>：NapCat 在容器里时，
     * 宿主机的 {@code 127.0.0.1} 在容器内指向<b>容器自己</b>，链接必然拉不到。
     * 所以只有在"确认 NapCat 就在本机且非容器"时才该用它 —— 这里把它放到最后。
     *
     * @return 形如 {@code http://172.17.0.1:2335} 的基址；实在推断不出时返回 {@code null}
     */
    public String baseUrl() {
        String explicit = loadDSConfig.stringOf(LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL, null);
        if (explicit != null) {
            return explicit.endsWith("/")
                    ? explicit.substring(0, explicit.length() - 1) : explicit;
        }
        if (actualPort <= 0) {
            return null;
        }
        boolean napCatIsLocal = napCatLooksLocal();
        List<String> candidates = candidateAddresses(napCatIsLocal);
        for (String ip : candidates) {
            return "http://" + ip + ":" + actualPort;
        }
        return null;
    }

    /** 把候选地址按优先级算出来（也给日志/命令用，让人能一眼看出到底选了谁、还有哪些备选）。 */
    private List<String> candidateAddresses(boolean napCatIsLocal) {
        List<String> ordered = new ArrayList<>();
        Map<String, String> byInterface = ipv4ByInterface();

        // ② docker 网桥：容器（尤其默认 bridge 上的）经它访问宿主机
        for (Map.Entry<String, String> e : byInterface.entrySet()) {
            if (isDockerBridge(e.getKey(), e.getValue())) {
                ordered.add(e.getValue());
            }
        }
        // ③ 去往 WS 对端的出口地址
        String outbound = outboundAddressForNapCat();
        if (outbound != null && !ordered.contains(outbound)) {
            ordered.add(outbound);
        }
        // ③' 其余非回环地址
        for (Map.Entry<String, String> e : byInterface.entrySet()) {
            if (!isLoopback(e.getKey()) && !ordered.contains(e.getValue())) {
                ordered.add(e.getValue());
            }
        }
        // ④ 回环放最后（容器场景下它是错的，只在明确本机非容器时才有意义）
        if (napCatIsLocal && outbound == null) {
            ordered.add("127.0.0.1");
        }
        return ordered;
    }

    /**
     * NapCat 是不是就在本机。
     *
     * <p>判据：{@code shiro.ws.client.url} 的 host 是回环地址，或正好等于本机某个网卡地址。
     * 取不到配置时按"不确定"处理（返回 false，于是不会把 127.0.0.1 当首选）。
     */
    private boolean napCatLooksLocal() {
        String host = hostOf(napCatWsUrl);
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host)) {
            return true;
        }
        return ipv4ByInterface().containsValue(host);
    }

    /** 从 {@code ws://host:port/...} 里取 host；取不到返回 {@code null}。 */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String uri = url.trim();
            if (!uri.contains("://")) {
                uri = "ws://" + uri;
            }
            String host = URI.create(uri).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查"从本机去 NapCat 会走哪张网卡"。
     *
     * <p>手法：对一个 <b>UDP socket 调 connect</b>。
     * UDP 的 connect 不发任何报文，只是让内核按路由表选一条路 —— 于是
     * {@code getLocalAddress()} 就给出去往对端的出口地址。<b>零流量、零副作用</b>，
     * 比"真连一下 TCP"礼貌得多（后者会在对方 WS 服务上留下一串无意义的连入记录）。
     */
    private String outboundAddressForNapCat() {
        String host = hostOf(napCatWsUrl);
        if (host == null || "localhost".equalsIgnoreCase(host) || host.startsWith("127.")) {
            return null;
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(host), 3001);
            InetAddress local = socket.getLocalAddress();
            return local instanceof Inet4Address ? local.getHostAddress() : null;
        } catch (Exception e) {
            log.debug("推断出口地址失败（不影响功能，只是少一个候选）：{}", e.toString());
            return null;
        }
    }

    /** 本机所有非回环的 IPv4，按网卡名归集（网卡名 → 地址）。 */
    private static Map<String, String> ipv4ByInterface() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.putIfAbsent(nic.getName(), addr.getHostAddress());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("枚举网卡失败：{}", e.toString());
        }
        return result;
    }

    private static boolean isLoopback(String nicName) {
        return nicName.startsWith("lo");
    }

    /**
     * 是不是 docker 网桥。
     *
     * <p>两条判据都要，缺一不可：
     * 网卡名（{@code docker0} / {@code br-xxxx}）说明"它是个桥"，
     * 而 {@code 172.16/12} 的私有段在默认配置下就是 docker 用的地址池。
     * 只看名字会漏掉自定义网络、只看网段会误伤把内网建在 172 段的机器。
     */
    private static boolean isDockerBridge(String nicName, String ip) {
        boolean nameLooksBridge = nicName.startsWith("docker") || nicName.startsWith("br-");
        boolean inDockerRange = ip.startsWith("172.17.") || ip.startsWith("172.18.")
                || ip.startsWith("172.19.") || ip.startsWith("172.2") || ip.startsWith("172.3");
        return nameLooksBridge && inDockerRange;
    }

    /** 把候选地址与最终选择打进日志 —— <b>出问题时这一行就是答案</b>。 */
    private void logCandidates() {
        if (actualPort <= 0) {
            return;
        }
        List<String> candidates = candidateAddresses(napCatLooksLocal());
        StringBuilder sb = new StringBuilder("录播下载基址候选（NapCat 视角看哪个通就用哪个）：");
        if (candidates.isEmpty()) {
            sb.append("(一个都推断不出，请在 config 表配置 ")
                    .append(LoadDSConfig.KEY_BILI_RECORD_PUBLIC_BASE_URL).append(")");
        } else {
            for (String ip : candidates) {
                sb.append("\n    http://").append(ip).append(':').append(actualPort);
            }
        }
        String chosen = baseUrl();
        sb.append("\n  当前采用：").append(chosen == null ? "(无，下载将退回本地路径模式)" : chosen);
        log.info(sb.toString());
    }

    /** 供命令回显：当前基址 + 候选清单（人排障用）。 */
    public String describe() {
        if (server == null || actualPort <= 0) {
            return "未启动（下载只能走本地路径模式）";
        }
        List<String> candidates = candidateAddresses(napCatLooksLocal());
        StringBuilder sb = new StringBuilder("监听 ").append(bindAddress).append(':').append(actualPort)
                .append("\n当前基址：").append(baseUrl() == null ? "(无)" : baseUrl())
                .append("\n候选：");
        if (candidates.isEmpty()) {
            sb.append("(无)");
        } else {
            for (String ip : candidates) {
                sb.append("\n  http://").append(ip).append(':').append(actualPort);
            }
        }
        sb.append("\nNapCat WS 地址：").append(napCatWsUrl == null || napCatWsUrl.isBlank() ? "(未配置)" : napCatWsUrl);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 请求处理

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                sendText(exchange, 405, "只支持 GET / HEAD\n");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || !path.startsWith(PREFIX)) {
                sendText(exchange, 404, "not found\n");
                return;
            }
            String token = path.substring(PREFIX.length());
            Entry entry = published.get(token);
            if (entry == null) {
                sendText(exchange, 404, "链接不存在或已失效\n");
                return;
            }
            if (entry.expireAt() < System.currentTimeMillis()) {
                published.remove(token);
                sendText(exchange, 410, "链接已过期，请重新获取\n");
                return;
            }
            if (!Files.isRegularFile(entry.file())) {
                published.remove(token);
                sendText(exchange, 404, "文件不存在（可能已被容量巡检清理）\n");
                return;
            }

            long total = Files.size(entry.file());
            // HEAD 只回头部，不消耗次数、不读内容（客户端常用它探大小）
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Type", HttpFileSupport.contentType(entry.name()));
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(total));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            // 真正下载才计数：次数上限是为了"链接发出去就不管"的场景，HEAD 不该消耗它
            if (entry.remaining().getAndDecrement() <= 0) {
                entry.remaining().incrementAndGet();
                sendText(exchange, 429, "该链接下载次数已用尽，请重新获取\n");
                return;
            }

            // ⚠️ 是 getRequestHeaders().getFirst(...)，不是 getRequestHeader(...)
            //    —— 后者在这个 JDK 自带的 API 里不存在（编译期就能发现，别照抄别的 HTTP 库的写法）
            // Range 解析与"按范围搬运字节"都在 HttpFileSupport 里 —— 网页预览那条路也用同一份，
            // 免得两个出口对"越界/负数/后缀写法"的处理悄悄分叉（那种分叉不报错，只会多读几字节）
            HttpFileSupport.ByteRange range = HttpFileSupport.parseRange(
                    exchange.getRequestHeaders().getFirst("Range"), total);
            if (range == null) {
                sendRangeError(exchange, total);
                return;
            }
            serve(exchange, entry, total, range);
        } catch (Exception e) {
            log.warn("录播文件服务处理请求出错：{}", e.toString());
            try {
                sendText(exchange, 500, "server error\n");
            } catch (Exception ignored) {
                // 响应可能已经开始写了，这里再抛也没意义
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * 发文件本体（支持单段 Range）。
     *
     * <p>为什么要支持 Range：一场录播动辄几百 MB 到几 GB，客户端断线重试是常态；
     * 不支持 Range 就得从 0 重来，而这类"传一半失败"在跨容器/跨机的场景里很常见。
     *
     * <p>Range 的解析与按范围搬运都委托给 {@link HttpFileSupport}（与网页预览共用一份）。
     */
    private void serve(HttpExchange exchange, Entry entry, long total, HttpFileSupport.ByteRange range)
            throws IOException {
        long start = range.start();
        long end = range.end();
        long length = range.length();

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", HttpFileSupport.contentType(entry.name()));
        headers.set("Accept-Ranges", "bytes");
        headers.set("Content-Disposition",
                "attachment; filename=\"" + HttpFileSupport.safeAsciiName(entry.name()) + "\"");
        if (range.partial()) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + total);
            exchange.sendResponseHeaders(206, length);
        } else {
            exchange.sendResponseHeaders(200, length);
        }

        try (InputStream in = Files.newInputStream(entry.file());
             OutputStream out = exchange.getResponseBody()) {
            HttpFileSupport.copyRange(in, out, start, length);
        }
    }

    private static void sendRangeError(HttpExchange exchange, long total) throws IOException {
        exchange.getResponseHeaders().set("Content-Range", "bytes */" + total);
        sendText(exchange, 416, "range not satisfiable\n");
    }

    private static void sendText(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 有没有可用基址（给交付逻辑判断"能不能走 url 模式"）。 */
    public boolean available() {
        return server != null && actualPort > 0 && baseUrl() != null;
    }

    /**
     * 实际监听的端口；未启动时为 {@code 0}。
     *
     * <p>存在的意义：交付失败时要在提示里拼一条"配成这样就行"的示例基址，
     * 那需要端口。**别再从 {@link #describe()} 的文本里正则抠端口**
     * —— 那是拿展示格式当接口用，改一次文案就静默失效。
     */
    public int port() {
        return actualPort;
    }

    /** 已登记的待下载数量（排障用）。 */
    public int pendingCount() {
        purgeExpired();
        return published.size();
    }

    /** 候选地址清单（供命令展示）。 */
    public List<String> candidates() {
        List<String> list = candidateAddresses(napCatLooksLocal());
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /**
     * <b>对外</b>候选地址 —— 给"录播网页"用（那是要群成员的浏览器去访问的）。
     *
     * <p>⚠️ <b>和 {@link #candidates()} 的差别不是排序，是筛选</b>，别拿那个替代这个：
     * 这里<b>排除 docker 网桥</b>。网桥地址（{@code 172.17.0.1} 之类）对 NapCat 容器是必经之路，
     * 对<b>群成员的浏览器</b>却是"容器网段的地址"——从外面根本路由不到，
     * 而它的排序又恰好排在前面（网桥优先是给 NapCat 定的），拿来当网页基址会得到
     * 一个<b>看着像域名、点开超时</b>的链接。
     *
     * <p>顺序：去往 NapCat 的出口地址优先（那通常就是本机的对外网卡），其余非网桥地址次之。
     * 回环不参与 —— 网页不可能是给自己看的。
     *
     * <p>🔴 <b>网桥过滤必须同样作用在"出口地址"上</b>（2026-10-05 修）：
     * {@code outboundAddressForNapCat()} 是 UDP 探测出来的，<b>没有网卡名</b>，
     * 早先直接 {@code ordered.add(outbound)} 就坐上了第一位、绕过了下面的 {@link #isDockerBridge}。
     * 而它恰恰是最容易踩雷的那条：线上 NapCat 跑在 docker 里（{@code 172.17.0.2}），
     * 探测出来的本地地址就是 <b>{@code docker0 = 172.17.0.1}</b> ⇒ 网页基址变成
     * {@code http://172.17.0.1:2233}，<b>群成员的浏览器永远打不开</b>，而命令还会报"成功"。
     * 所以这里先反查网卡名再判；判不过就<b>下沉到末尾</b>（不静默丢掉，内网部署时它仍是个可见候选）。
     *
     * <p>⚠️ 认清这套推断的<b>能力边界</b>：它只能排掉"按名字能看出是网桥"的那些。
     * 平台认不出名字的虚拟网卡（Windows 的 {@code eth11}/{@code net7} 分别是 WSL 与代理 TUN）
     * 仍会留在候选里 —— 所以调用方不能把"推断出地址"当成"地址可用"：
     * {@link com.esdllm.service.RecordWebAuth#baseUrl()} 会再筛一道"是不是公网"，
     * 筛不过就不下发链接，改用 {@link com.esdllm.service.RecordWebAuth#baseUrlAdvice()} 给出指引。
     *
     * @return 候选地址（可能为空：机器上只有回环时）；调用方应把"空"当成
     *         "推断不出来，请显式配置基址"，而不是随便挑一个
     */
    public List<String> externalCandidates() {
        Map<String, String> byNic = ipv4ByInterface();
        List<String> ordered = new ArrayList<>();
        String outbound = outboundAddressForNapCat();
        boolean outboundIsBridge = outbound != null
                && isDockerBridge(nicOf(byNic, outbound), outbound);
        if (outbound != null && !outboundIsBridge) {
            ordered.add(outbound);
        }
        for (Map.Entry<String, String> e : byNic.entrySet()) {
            if (!isDockerBridge(e.getKey(), e.getValue()) && !ordered.contains(e.getValue())) {
                ordered.add(e.getValue());
            }
        }
        if (outboundIsBridge && !ordered.contains(outbound)) {
            ordered.add(outbound);
        }
        return ordered;
    }

    /** 反查某个 IP 属于哪张网卡（UDP 探测出来的地址没有网卡名，只能这样还原）。 */
    private static String nicOf(Map<String, String> byNic, String ip) {
        for (Map.Entry<String, String> e : byNic.entrySet()) {
            if (ip.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return "";
    }
}
