package com.esdllm.service;

import com.esdllm.config.LoadDSConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * <b>ffmpeg 运行时获取器</b> —— 让「下载 Release 的 jar 直接启动」这件事成立：
 * 机器上没有 ffmpeg 时自动去镜像拉一个静态二进制回来。
 *
 * <h2>为什么是"下载"而不是"打进 jar"</h2>
 *
 * 两条路都试过权衡，最终选下载：
 * <ul>
 *   <li><b>打进 jar</b>：jar 会从 61MB 涨到 89MB（只放 Linux 的话）。更要命的是
 *       ffmpeg 的 static 构建是 <b>GPL</b>（含 libx264/libx265），公开仓库推 Release
 *       属于<b>再分发</b>，得配套履行 GPL 义务（许可证 + 源码 offer）。</li>
 *   <li><b>运行时下载</b>：jar 不变、不承担再分发责任；代价是首次需要外网。
 *       实测镜像站（npmmirror）速度可达 10MB/s，28MB 的包几秒就完。</li>
 * </ul>
 *
 * <h2>查找顺序（先便宜的、后动网络的）</h2>
 * <ol>
 *   <li>{@code biliDownloadFfmpegPath} 显式配置的路径（最高优先，给"我自己指定"的人）；</li>
 *   <li>{@code ./bin/ffmpeg} —— 本类上次下载的落点；</li>
 *   <li>PATH 里的 {@code ffmpeg}（系统装的）；</li>
 *   <li>以上都没有 且 {@code biliDownloadFfmpegAutoFetch} 开着 → 下载。</li>
 * </ol>
 * 每一步都是<b>真的跑一次 {@code -version}</b> 才算数 —— 文件存在不等于能用
 * （半截文件、架构不匹配、没有执行位都会在这里现形）。
 *
 * <h2>几条刻意的设计</h2>
 * <ul>
 *   <li><b>预取是异步的</b>：下 28MB 可能要几秒到几十秒，绝不能让它在 {@code @PostConstruct}
 *       里同步跑 —— 那会把整个机器人启动卡住。</li>
 *   <li><b>落盘是原子的</b>：先下到 {@code *.part}、校验过、探活过，最后才
 *       {@code Files.move} 到最终名。所以 {@code ./bin/ffmpeg} <b>存在 ⇒ 一定能跑</b>，
 *       不会出现"上次下到一半、这次以为已经有"。</li>
 *   <li><b>校验分级</b>：默认源的两个平台内置了 SHA256（强校验）；换了自定义 URL、
 *       或平台没有内置摘要时，退化为"解压成功 + 能跑 {@code -version}"（弱校验）。
 *       之所以敢留这个口子：这年头镜像被投毒的概率远小于"下载被截断"，
 *       而弱校验足以挡住后者。</li>
 *   <li><b>复用 {@code biliProxy}</b>：本机的系统代理时通时断，而镜像站国内直连就够了。
 *       所以默认<b>不走任何代理</b>；只有当用户明确配了 {@code biliProxy} 时才跟着走。</li>
 *   <li><b>失败是静默降级</b>：拿不到 ffmpeg 不影响机器人其它任何功能，
 *       只是下载类命令会回一句"未就绪"。跟 {@code CredentialGuard} 的
 *       "无 Cookie 零请求"是同一条纪律：能力不具备时提前说清楚，而不是等运行时报错。</li>
 * </ul>
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Component
public class FfmpegProvider {

    /** 下载落点目录，相对<b>进程工作目录</b>（与 {@code ./resources/} 同一口径）。 */
    private static final String CACHE_DIR = "bin";

    /** 默认下载源（npmmirror 二进制镜像，国内直连实测 10MB/s）。 */
    private static final String DEFAULT_BASE =
            "https://registry.npmmirror.com/-/binary/ffmpeg-static/b6.1.1/";

    private static final int PROBE_TIMEOUT_SECONDS = 5;

    /**
     * 内置摘要：<b>平台 → (sha256, 解压后字节数)</b>。
     *
     * <p>只钉了实测过的两个平台。没钉的平台（arm64 / darwin）仍然能下载，
     * 只是少了强校验这一层（见类注释的"校验分级"）。
     */
    private static final Map<String, Pinned> PINNED = Map.of(
            "linux-x64", new Pinned("bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa", 79826272L),
            "win32-x64", new Pinned("8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77", 82797568L)
    );

    /** 不支持代理时的选择器：返回空列表 = 直连。 */
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of();
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException e) {
            // 直连失败不需要额外动作，调用方会收到原始异常
        }
    };

    @Resource
    private LoadDSConfig loadDSConfig;

    /** 就绪时的可执行文件路径；{@code null} = 未就绪。 */
    private volatile String exePath;

    /** 人可读的状态，供日志/命令回显：{@code READY} / {@code FETCHING} / {@code PENDING} / {@code UNAVAILABLE}。 */
    private volatile String status = "PENDING";

    /**
     * 未就绪的<b>具体原因</b>。
     *
     * <p>为什么要单独存一份：{@code UNAVAILABLE} 有三条完全不同的来路
     * （配置的路径跑不起来 / 装了但自动获取关着 / 下载失败），
     * 而回显给用户的如果是笼统的"未安装"，就会把人引向错误的排查方向 ——
     * 这正是本项目一直在防的那类"报错离真因很远"。
     */
    private volatile String reason = "尚未检查";

    private final AtomicBoolean fetching = new AtomicBoolean();

    private record Pinned(String sha256, long unpackedBytes) {
    }

    /**
     * 启动时做两件事：<b>同步探活</b>（便宜，只起几个进程）把状态定下来，
     * 需要时再<b>异步下载</b>。
     *
     * <p>同步部分只有探活，所以即使一个候选都没有，这里也只是几次几十毫秒的
     * {@code ProcessBuilder}；真正的网络动作全部在后台线程里。
     */
    @PostConstruct
    public void warmup() {
        // ① 显式配置的路径：**只用它**。跑不起来就是不可用 —— 既不偷偷换别的，也不触发出乎意料的下载。
        //    理由：静默换用另一个版本正是本项目一直在防的那类"静默降级"，
        //    而"我指定了它、它却用了别的"远比"功能暂时不可用"难查。
        String configured = stringOf(LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH);
        if (configured != null) {
            if (probe(configured)) {
                exePath = configured;
                status = "READY";
                reason = "已就绪（来自 " + LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH + "）";
                log.info("[ffmpeg] 已就绪（来自 {}）：{}", LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH, configured);
            } else {
                status = "UNAVAILABLE";
                reason = LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH + " 指定的 [" + configured
                        + "] 跑不起来（文件不存在、没有执行位，或不是可用的 ffmpeg）";
                log.warn("[ffmpeg] {} 指定的 [{}] 跑不起来（文件不存在、没有执行位，或不是可用的 ffmpeg）"
                                + "⇒ 下载类功能不可用。**不会**回退到 PATH/缓存，也不会自动下载 —— "
                                + "请修正该配置，或删掉它改用自动查找",
                        LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH, configured);
            }
            return;
        }

        // ② 自动查找：./bin/ 缓存 → PATH
        exePath = resolve();
        if (exePath != null) {
            status = "READY";
            reason = "已就绪";
            log.info("[ffmpeg] 已就绪：{}", exePath);
            return;
        }

        // ③ 一个都没有，看要不要自己取一个
        if (!autoFetch()) {
            status = "UNAVAILABLE";
            reason = "本机没有可用的 ffmpeg，且自动获取（"
                    + LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_AUTO_FETCH + "）已关闭";
            log.info("[ffmpeg] 未找到可用的 ffmpeg，且 biliDownloadFfmpegAutoFetch 已关闭 ⇒ "
                    + "下载类功能不可用（不影响其它功能）。要么自行安装，要么用 {} 指定路径",
                    LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_PATH);
            return;
        }
        status = "FETCHING";
        reason = "正在后台获取";
        log.info("[ffmpeg] 本机没有可用的 ffmpeg，开始后台获取（约 28MB，来源 {}）…", sourceUrl());
        Thread worker = new Thread(this::fetchOnce, "ffmpeg-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /** 是否已就绪。 */
    public boolean isReady() {
        return exePath != null;
    }

    /** 已就绪的可执行文件路径。调用方在触发下载类操作前应当先问这个。 */
    public Optional<String> executable() {
        return Optional.ofNullable(exePath);
    }

    /** 当前状态（人可读）。 */
    public String status() {
        return status;
    }

    /** 供命令回显的一句话说明。<b>未就绪时带上具体原因</b>，别让用户去猜是"没装"还是"装了但被关了"。 */
    public String describe() {
        return switch (status) {
            case "READY" -> "已就绪：" + exePath;
            case "FETCHING" -> "正在后台获取，请稍后再试";
            case "UNAVAILABLE" -> "不可用：" + reason;
            default -> "尚未检查";
        };
    }

    // ------------------------------------------------------------------ 查找

    /**
     * 自动查找：{@code ./bin/} 缓存 → PATH，返回第一个<b>真的能跑</b>的。
     *
     * <p>刻意<b>不</b>包含"显式配置"那一路 —— 它在 {@link #warmup()} 里单独处理，
     * 且规矩相反：只用它、跑不起来也不回退。两件事分开写，是因为一个有回退、一个没有，
     * 混在一个循环里最容易改着改着就悄悄给显式配置也加上回退。
     *
     * @return 可执行文件路径；都没有时返回 {@code null}
     */
    private String resolve() {
        Path cached = cacheFile();
        if (Files.isRegularFile(cached) && probe(cached.toString())) {
            return cached.toAbsolutePath().toString();
        }

        // 交给 PATH。找不到可执行文件时探活会抛 IOException 并返回 false。
        if (probe("ffmpeg")) {
            return "ffmpeg";
        }
        return null;
    }

    /** 缓存文件路径：{@code ./bin/ffmpeg}（Windows 上是 {@code ffmpeg.exe}）。 */
    private Path cacheFile() {
        return Paths.get(CACHE_DIR, isWindows() ? "ffmpeg.exe" : "ffmpeg");
    }

    /**
     * 跑一次 {@code ffmpeg -version}，退出码 0 才算可用。
     *
     * <p>🔴 <b>必须读完输出</b>：不读的话子进程可能因为管道缓冲区写满而卡住，
     * {@code waitFor} 就会一直等到超时。这是 {@code ProcessBuilder} 最经典的坑，
     * 短命令尤其容易在这里骗过测试。
     */
    boolean probe(String candidate) {
        Process process = null;
        try {
            process = new ProcessBuilder(candidate, "-hide_banner", "-version")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            if (process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return true;
            }
            process.destroyForcibly();
            return false;
        } catch (IOException e) {
            // 找不到文件 / 没有执行权限 / 架构不匹配都会落到这里，不需要区分 —— 结论都是"不能用"
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 恢复中断标志，别吞掉
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ 获取

    /**
     * 执行一次下载安装。<b>串行</b>（{@code synchronized} + {@link #fetching}）：
     * 启动预取和将来的懒加载可能同时进来，不能下两份。
     */
    private synchronized void fetchOnce() {
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        Path partGz = null;
        Path partExe = null;
        try {
            String platform = platformId();
            if (platform == null) {
                fail("无法识别的平台（os.name=" + System.getProperty("os.name")
                        + ", os.arch=" + System.getProperty("os.arch") + "）");
                return;
            }

            Path dir = Paths.get(CACHE_DIR).toAbsolutePath().normalize();
            Files.createDirectories(dir);

            partGz = dir.resolve("ffmpeg-" + platform + ".gz.part");
            // 临时可执行文件保留正确后缀：Windows 上不以 .exe 结尾的文件根本执行不了，
            // 探活会直接失败（而这不是"下载坏了"，是名字起错了）。
            partExe = dir.resolve(isWindows() ? "ffmpeg.part.exe" : "ffmpeg.part");

            String url = sourceUrlFor(platform);
            download(url, partGz);

            Pinned pinned = usesDefaultBase() ? PINNED.get(platform) : null;
            if (pinned != null) {
                String actual = sha256(partGz);
                if (!pinned.sha256().equalsIgnoreCase(actual)) {
                    fail("下载内容校验失败（SHA256 不符）—— 期望 " + pinned.sha256() + "，实得 " + actual
                            + "；可能是镜像返回了错误页或传输被截断，本次放弃");
                    return;
                }
            } else {
                log.info("[ffmpeg] 该平台没有内置摘要，跳过 SHA256 校验（改用「解压 + 能跑」作为判据）");
            }

            gunzip(partGz, partExe);
            long size = Files.size(partExe);
            if (pinned != null && size != pinned.unpackedBytes()) {
                fail("解压后体积不符（期望 " + pinned.unpackedBytes() + " 字节，实得 " + size + "）");
                return;
            }
            makeExecutable(partExe);
            if (!probe(partExe.toString())) {
                fail("下载回来的文件跑不起来（-version 未正常退出）—— 可能架构不匹配");
                return;
            }

            // 前面全部通过，才让它出现在最终位置。这一步之后 ./bin/ffmpeg 就是"一定能跑"的。
            Files.move(partExe, cacheFile().toAbsolutePath(),
                    StandardCopyOption.REPLACE_EXISTING);
            exePath = cacheFile().toAbsolutePath().toString();
            status = "READY";
            log.info("[ffmpeg] 获取完成：{}（{} MB，平台 {}）", exePath, size / 1048576, platform);
        } catch (IOException e) {
            fail("网络或磁盘错误：" + e);
        } finally {
            deleteQuietly(partGz);
            deleteQuietly(partExe);
            fetching.set(false);
        }
    }

    private void fail(String why) {
        status = "UNAVAILABLE";
        reason = why;
        log.warn("[ffmpeg] 获取失败 ⇒ 下载类功能不可用（不影响其它功能）：{}", why);
    }

    // ------------------------------------------------------------------ 下载 / 解压

    private void download(String url, Path target) throws IOException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);   // 镜像站会 302 到实际文件
        InetSocketAddress proxy = parseProxy(stringOf(LoadDSConfig.KEY_BILI_PROXY));
        builder.proxy(proxy == null ? NO_PROXY : ProxySelector.of(proxy));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "XatiiBot-ffmpeg-fetch/1.0")
                .GET()
                .build();

        long started = System.currentTimeMillis();
        HttpResponse<Path> response = send(builder.build(), request, target);
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + "（" + url + "）");
        }
        log.info("[ffmpeg] 下载完成 {}MB，耗时 {}ms", Files.size(target) / 1048576,
                System.currentTimeMillis() - started);
    }

    /** 发一次请求；失败重试一次（镜像偶发抖动，重试比"整轮失败"划算）。 */
    private HttpResponse<Path> send(HttpClient client, HttpRequest request, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Files.deleteIfExists(target);
                return client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            } catch (IOException e) {
                last = e;
                if (attempt < 2) {
                    log.info("[ffmpeg] 第 {} 次下载失败（{}），重试一次", attempt, e.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断", e);
            }
        }
        throw last;
    }

    private void gunzip(Path gz, Path out) throws IOException {
        Files.deleteIfExists(out);
        try (InputStream fileIn = Files.newInputStream(gz, StandardOpenOption.READ);
             GZIPInputStream gzIn = new GZIPInputStream(fileIn, 1 << 16);
             var outStream = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
            gzIn.transferTo(outStream);
        }
    }

    private void makeExecutable(Path file) throws IOException {
        if (isWindows()) {
            return;   // Windows 上靠后缀识别可执行，没有 POSIX 权限位
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // 文件系统不支持 POSIX 权限（比如某些挂载），交给后面的探活判定
            log.info("[ffmpeg] 该文件系统不支持设置 POSIX 权限，跳过（交由探活判定）");
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.info("[ffmpeg] 清理临时文件失败（不影响功能）：{} —— {}", file, e.toString());
        }
    }

    // ------------------------------------------------------------------ 配置 / 平台

    /** 平台标识，如 {@code linux-x64}；识别不了时返回 {@code null}。 */
    public static String platformId() {
        return platformId(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** 平台标识（可注入 os.name / os.arch，便于跨平台自测）。 */
    public static String platformId(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64");
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("linux")) {
            return x64 ? "linux-x64" : (arm64 ? "linux-arm64" : null);
        }
        if (os.contains("windows")) {
            return x64 ? "win32-x64" : null;
        }
        if (os.contains("mac")) {
            return arm64 ? "darwin-arm64" : (x64 ? "darwin-x64" : null);
        }
        return null;
    }

    private String sourceUrl() {
        String platform = platformId();
        return platform == null ? "(未知平台)" : sourceUrlFor(platform);
    }

    /** 下载地址：配了 {@code biliDownloadFfmpegUrl} 就用它（{@code {platform}} 会被替换），否则用默认镜像。 */
    private String sourceUrlFor(String platform) {
        String custom = stringOf(LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_URL);
        if (custom != null) {
            return custom.replace("{platform}", platform);
        }
        return DEFAULT_BASE + "ffmpeg-" + platform + ".gz";
    }

    private boolean usesDefaultBase() {
        return stringOf(LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_URL) == null;
    }

    private boolean autoFetch() {
        return loadDSConfig.isEnabled(LoadDSConfig.KEY_BILI_DOWNLOAD_FFMPEG_AUTO_FETCH, true);
    }

    /** 读一个字符串配置：键缺失/空白都当作"没配"。 */
    private String stringOf(String key) {
        String raw = loadDSConfig.getConfigMap().get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * 解析代理配置（复用 {@code biliProxy}）：接受 {@code host:port} 或 {@code http://host:port}。
     * 解析不出来就当没配（直连）。
     */
    public static InetSocketAddress parseProxy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return null;
        }
        try {
            return new InetSocketAddress(value.substring(0, colon),
                    Integer.parseInt(value.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            // 端口不是数字（NumberFormatException 是它的子类）、或主机名非法 —— 都当作"没配代理"
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
