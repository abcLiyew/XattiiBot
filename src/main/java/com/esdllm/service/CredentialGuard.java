package com.esdllm.service;

import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.login.CredentialStatus;
import com.esdllm.config.LoadDSConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B 站<b>凭据（Cookie）状态的主动探测</b>：把「Cookie 是不是废了」从一个只能靠翻日志猜的问题，
 * 变成一个可判的枚举值。
 *
 * <p><b>它治的病</b>（G1 + G5）：Cookie 过期后，动态推送是<b>静默失败</b>的 ——
 * 用户看到的现象是"机器人突然不推了"，日志里只有一条笼统的拉取失败；
 * 而 {@code -352}（凭据失效）与 {@code -412}（路径/出口被封）在日志里长得几乎一样，
 * 处置办法却完全相反（重新登录 vs 换源/换代理）。本类用
 * {@link Login#getCredentialStatus()}（打 {@code x/web-interface/nav}）给出权威判据。
 *
 * <h3>🔴 节流是必需的，不是优化</h3>
 * B 站的 412 是<b>请求密度敏感</b>型风控（真机实测 1 秒内 3 个请求即触发），而探测走的
 * {@code api.bilibili.com} 正是被盯的那条通道。更要注意的是探测本身的成本：
 * <b>服务端认为已登录时，库还会再问一次 {@code cookie/info}</b>（拿"该不该刷新"），
 * 也就是一次探测 = <b>2 次请求</b>。
 * 所以：
 * <ul>
 *   <li>{@link #probe} 有最小间隔（{@value #MIN_PROBE_INTERVAL_MS}ms），连续失败轮不会每轮都探；</li>
 *   <li>没配 Cookie 时<b>直接返回，一个请求都不发</b>（匿名模式没什么可证的）；</li>
 *   <li>同一时刻只允许一次探测在跑（{@link #probing}）；</li>
 *   <li>常态（Cookie 有效）下由「推送失败事件」驱动，请求数不变；
 *       再加一条低频兜底（见 {@link #maybeProbeFallback}）覆盖"一整天没有动态可推"的静默场景。</li>
 * </ul>
 *
 * <h3>为什么不允许 {@code probe} 抛异常</h3>
 * 调用方都在推送 / 消息处理的链路上，探测失败<b>不能反过来把链路打断</b> ——
 * 所以本类把一切异常收敛成 {@link Verdict#UNKNOWN}，并且明确区分它和
 * {@link Verdict#INVALID}：前者是"网络/出口的问题，该重试/退避"，
 * 后者是"凭据废了，该重新登录"。这两件事的处置恰好相反，混在一起就白做了。
 *
 * <p><b>拿不到结论就别下结论</b>：只有服务端明确回 {@code isLoggedIn()==false} 才算
 * {@code INVALID}；HTTP 非 2xx、超时、响应不是合法 JSON 一律算 {@code UNKNOWN}，
 * 免得把一次网络抖动误判成"Cookie 失效"去骚扰用户。
 */
@Slf4j
@Component
public class CredentialGuard {

    /**
     * 两次探测之间的最小间隔（毫秒）。
     *
     * <p>取 10 分钟：一轮动态推送是 60 秒，失败时会连续多轮进来 —— 没有这道闸，
     * 每轮都探（每次 1~2 个请求）等于在风控窗口上再叠一层流量。
     * 而凭据失效是一个"以小时/天计"的状态，10 分钟的分辨率完全够。
     */
    private static final long MIN_PROBE_INTERVAL_MS = 10 * 60_000L;

    /** 兜底探测的默认间隔（小时）；配置 {@link LoadDSConfig#KEY_BILI_CREDENTIAL_CHECK_HOURS} 可覆盖 */
    public static final int DEFAULT_CHECK_HOURS = 6;

    @Resource
    private LoadDSConfig loadDSConfig;

    /** 最近一次探测结果（含缓存复用），{@code null} 表示本次进程还没探过 */
    private volatile Status lastStatus = null;

    /** 上次真正发出探测请求的时刻；<b>失败也会更新</b>，否则失败风暴会把节流绕过去 */
    private volatile long lastProbeAt = 0L;

    /** 同一时刻只允许一次探测在跑 */
    private final AtomicBoolean probing = new AtomicBoolean(false);

    /** 探测的结局 */
    public enum Verdict {
        /** 没配 Cookie —— 匿名模式，不需要探测（也没法探） */
        NOT_CONFIGURED,
        /** 服务端认这枚凭据 */
        VALID,
        /** 服务端明确不认（通常是 {@code code=-101} 账号未登录）⇒ 该重新登录 */
        INVALID,
        /** 探测本身失败（网络 / HTTP 非 2xx）⇒ <b>不能下结论</b>，按"未知"处理 */
        UNKNOWN
    }

    /** 触发探测的原因，只用于日志归因 */
    public enum Reason {
        /** 动态推送失败后顺手探一次（事件驱动，常态零开销） */
        PUSH_FAILURE("推送失败"),
        /** 低频兜底定时 */
        FALLBACK("定时兜底"),
        /** 用户主动发的「cookie状态」命令 */
        USER_COMMAND("用户查询");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * 一次探测的结论（不可变）。
     *
     * <p>{@link #summary} 直接沿用库里的 {@code CredentialStatus#summary()}（它已经按"不含凭据值"的口径写过），
     * 失败时是异常消息 —— <b>两者都不含 Cookie 值</b>。
     */
    public static final class Status {

        private final Verdict verdict;
        private final String summary;
        private final long uid;
        private final String uname;
        private final int code;
        /** 服务端有没有回答"该不该刷新"这一项；{@code false} 时下一项没有意义 */
        private final boolean refreshChecked;
        /**
         * 服务端是否认为该换一枚新凭据了。
         *
         * <p>⚠️ 本库<b>没有实现刷新</b>（web 端那套换 Cookie 是浏览器内的 iframe + WASM 流程），
         * 所以这个 {@code true} 的当前含义就是"<b>请重新登录</b>"。
         */
        private final boolean refreshNeeded;
        /** 上次真正问服务端的时刻（毫秒）；失败也会更新。{@code 0} 表示没探过 */
        private final long probedAt;
        private final boolean stateChanged;
        private final boolean fromCache;

        private Status(Verdict verdict, String summary, long uid, String uname, int code,
                       boolean refreshChecked, boolean refreshNeeded, long probedAt,
                       boolean stateChanged, boolean fromCache) {
            this.verdict = verdict;
            this.summary = summary;
            this.uid = uid;
            this.uname = uname;
            this.code = code;
            this.refreshChecked = refreshChecked;
            this.refreshNeeded = refreshNeeded;
            this.probedAt = probedAt;
            this.stateChanged = stateChanged;
            this.fromCache = fromCache;
        }

        /** 没配 Cookie：不发请求，因此也没有"探测时刻" */
        static Status notConfigured() {
            return new Status(Verdict.NOT_CONFIGURED, "未配置 Cookie（匿名模式）", 0L, null, 0,
                    false, false, 0L, false, false);
        }

        /** 探测本身失败（网络 / 出口）—— ⚠️ 这<b>不是</b>"凭据失效" */
        static Status unknown(String message, long probedAt) {
            return new Status(Verdict.UNKNOWN, message, 0L, null, 0, false, false, probedAt,
                    false, false);
        }

        /**
         * 服务端给了明确答复。
         *
         * <p>⚠️ {@code isLoggedIn()==false} 是<b>返回值</b>而不是异常，这里如实映射成
         * {@link Verdict#INVALID}；只有真故障（HTTP 非 2xx、响应不合法）才该走 {@link #unknown}。
         */
        static Status of(CredentialStatus st, long probedAt) {
            return new Status(st.isLoggedIn() ? Verdict.VALID : Verdict.INVALID,
                    st.summary(), st.getUid(), st.getUname(), st.getCode(),
                    st.isRefreshChecked(), st.isRefreshNeeded(), probedAt, false, false);
        }

        public Verdict getVerdict() {
            return verdict;
        }

        /** 一行摘要（不含凭据值），可直接进日志或消息回复 */
        public String getSummary() {
            return summary;
        }

        public long getUid() {
            return uid;
        }

        public String getUname() {
            return uname;
        }

        public int getCode() {
            return code;
        }

        /** 服务端有没有回答"该不该刷新"；{@code false} 时 {@link #isRefreshNeeded()} 无意义 */
        public boolean isRefreshChecked() {
            return refreshChecked;
        }

        /** 服务端认为该换新凭据了 —— 本库不实现刷新，所以它的含义就是"请重新登录" */
        public boolean isRefreshNeeded() {
            return refreshNeeded;
        }

        /** 上次真正问服务端的时刻（毫秒）；失败也会更新。{@code 0} 表示没探过 */
        public long getProbedAt() {
            return probedAt;
        }

        /**
         * 与<b>上一次探测</b>相比结论是否变了。
         *
         * <p>告警去重就靠它：Cookie 一直失效时，每轮推送都会读到 {@code INVALID}，
         * 但只有<b>第一次</b>（以及恢复那一次）值得刷日志。
         *
         * <p>🔴 这个标记由 {@link CredentialGuard#remember} 在<b>写入之前</b>比对得出再回填 ——
         * 绝不能在构造时就写死 {@code false}：那会让所有"状态变化"判断永远为假，
         * 症状是"失效了但日志里一条告警都没有"（比报错难查得多）。
         */
        public boolean isStateChanged() {
            return stateChanged;
        }

        /** 本次结论是不是从缓存里取的（没发请求） */
        public boolean isFromCache() {
            return fromCache;
        }

        /** 明确"凭据废了"—— 这是唯一该提示用户重新登录的结论 */
        public boolean isInvalid() {
            return verdict == Verdict.INVALID;
        }

        private Status withStateChanged(boolean changed) {
            return new Status(verdict, summary, uid, uname, code, refreshChecked, refreshNeeded,
                    probedAt, changed, fromCache);
        }

        private Status asCached() {
            return new Status(verdict, summary, uid, uname, code, refreshChecked, refreshNeeded,
                    probedAt, false, true);
        }
    }

    /**
     * 探测一次，<b>带节流</b>。
     *
     * <p>用于推送失败等自动路径：最小间隔内的重复调用直接复用上次结论（不发请求）。
     *
     * @param reason 触发原因，仅用于日志
     * @return 探测结论，永不为 {@code null}
     */
    public Status probe(Reason reason) {
        return doProbe(reason, false);
    }

    /**
     * 探测一次，<b>绕过节流</b>。
     *
     * <p>只给用户主动触发的命令用（如「cookie状态」）—— 用户明确问了一次，
     * 就该拿到此刻的真实答案，而不是 10 分钟前的缓存。
     *
     * @param reason 触发原因，仅用于日志
     * @return 探测结论，永不为 {@code null}
     */
    public Status probeNow(Reason reason) {
        return doProbe(reason, true);
    }

    /**
     * 低频兜底：按配置的间隔（默认 {@value #DEFAULT_CHECK_HOURS} 小时）主动探一次。
     *
     * <p>覆盖的是"一整天没有动态可推 ⇒ 推送从不失败 ⇒ 事件驱动永远不触发"的静默场景。
     * 进程启动后也会立刻探一次（{@link #lastProbeAt} 初值为 0），
     * 这顺带实现了上游建议的"长驻进程启动时校验一次"。
     *
     * <p>间隔由 {@link LoadDSConfig#KEY_BILI_CREDENTIAL_CHECK_HOURS} 控制，
     * <b>≤0 表示关闭兜底</b>（只保留事件驱动）；配置改动即时生效，无需重启。
     */
    public void maybeProbeFallback() {
        int hours = configuredCheckHours();
        if (hours <= 0) {
            return;
        }
        if (!HttpPolicy.hasCookie()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastProbeAt != 0L && now - lastProbeAt < hours * 3600_000L) {
            return;
        }
        probe(Reason.FALLBACK);
    }

    /**
     * 最近一次探测结论（可能为 {@code null}，表示本次进程还没探过）。不发请求。
     */
    public Status lastStatus() {
        return lastStatus;
    }

    /** 读兜底间隔配置；缺失/非法时回落默认值 */
    private int configuredCheckHours() {
        String raw = loadDSConfig.getConfigMap().get(LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CHECK_HOURS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是整数（原值：{}），本次按默认 {} 小时处理",
                    LoadDSConfig.KEY_BILI_CREDENTIAL_CHECK_HOURS, raw, DEFAULT_CHECK_HOURS);
            return DEFAULT_CHECK_HOURS;
        }
    }

    /**
     * 真正干活的地方。
     *
     * @param reason         触发原因
     * @param bypassThrottle 是否跳过节流
     * @return 探测结论
     */
    private Status doProbe(Reason reason, boolean bypassThrottle) {
        // 没配 Cookie：一个请求都不发。匿名模式下"凭据状态"这个问题不成立。
        if (!HttpPolicy.hasCookie()) {
            Status status = remember(Status.notConfigured());
            if (status.isStateChanged() && reason != Reason.USER_COMMAND) {
                log.debug("B 站凭据探测（{}）：{}", reason.getText(), status.getSummary());
            }
            return status;
        }

        long now = System.currentTimeMillis();
        if (!bypassThrottle && lastProbeAt != 0L && now - lastProbeAt < MIN_PROBE_INTERVAL_MS) {
            Status cached = lastStatus;
            if (cached != null) {
                log.debug("B 站凭据探测（{}）距上次不足 {} 分钟，复用上次结论：{}",
                        reason.getText(), MIN_PROBE_INTERVAL_MS / 60_000, cached.getSummary());
                return cached.asCached();
            }
        }

        // 已有一次探测在跑：别叠请求，返回上次结论（可能是 null ⇒ 给个 UNKNOWN）
        if (!probing.compareAndSet(false, true)) {
            Status cached = lastStatus;
            log.debug("B 站凭据探测（{}）已有一次在进行中，本次跳过", reason.getText());
            return cached != null ? cached.asCached() : Status.unknown("上一次探测仍在进行中", 0L);
        }

        try {
            // ★ 先记时间再发请求：失败也要算"探过了"，
            //   否则连续失败的每一轮都会绕过节流，把一次故障放大成请求风暴。
            lastProbeAt = System.currentTimeMillis();
            CredentialStatus st = new Login().getCredentialStatus();
            if (st == null) {
                Status status = remember(Status.unknown("服务端返回空的凭据状态", lastProbeAt));
                log.warn("B 站凭据探测（{}）拿到空结果", reason.getText());
                return status;
            }
            // ⚠️ 凭据无效是「返回值」不是异常（见 Login#getCredentialStatus）：
            //    false 的含义是"该重新登录"，异常的含义是"该重试"，两者处置相反。
            Status status = remember(Status.of(st, lastProbeAt));
            logProbe(status, reason);
            return status;
        } catch (Throwable t) {
            // 探测失败 ≠ 凭据失效。这里绝不能返回 INVALID，否则一次网络抖动就会引导用户去重新登录。
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            Status status = remember(Status.unknown(message, lastProbeAt));
            if (status.isStateChanged()) {
                log.warn("B 站凭据探测（{}）失败（这属于网络/出口问题，不是凭据失效）：{}",
                        reason.getText(), message);
            } else {
                log.debug("B 站凭据探测（{}）仍失败：{}", reason.getText(), message);
            }
            return status;
        } finally {
            probing.set(false);
        }
    }

    /**
     * 记下结论，并把"与上一次相比是否变化"<b>回填</b>进去（返回的就是存下的那一个）。
     *
     * <p>🔴 顺序很关键：必须<b>先比对、再写入</b>。若先写 {@code lastStatus} 再比，
     * 比到的永远是自己，结论必然是"没变化" —— 那会让所有告警静默失效，
     * 而代码看上去完全正常（本类修过一个正是这样的真 bug）。
     *
     * <p>去重口径是 {@link Verdict} 而不是 summary 字符串：同一结论下 uid / 昵称可能变，
     * 但"该不该重新登录"没变，就不该再刷一次告警。
     */
    private Status remember(Status status) {
        Status previous = lastStatus;
        boolean changed = previous == null || previous.getVerdict() != status.getVerdict();
        Status recorded = status.withStateChanged(changed);
        lastStatus = recorded;
        return recorded;
    }

    /**
     * 按结论打日志，<b>只在结论变化时打</b>（避免每轮刷屏）。
     *
     * <p>失效文案刻意带上「Cookie 已失效」与「设置cookie」两个可 grep 的锚点 ——
     * 排障时一条 {@code grep "Cookie 已失效"} 就能确认根因，不必再去猜 -352 还是 -412。
     */
    private void logProbe(Status status, Reason reason) {
        if (!status.isStateChanged()) {
            log.debug("B 站凭据探测（{}）：{}", reason.getText(), status.getSummary());
            return;
        }
        switch (status.getVerdict()) {
            case VALID -> log.info("B 站凭据有效（{}）：{}", reason.getText(), status.getSummary());
            case INVALID -> log.error("B 站 Cookie 已失效，请重新设置cookie（{}）：{}。"
                            + "修法：私聊发「登录」扫码，或发「设置cookie SESSDATA:...」。"
                            + "注意这与 -412（路径/出口被封）不是一回事，换源与换代理都解决不了。",
                    reason.getText(), status.getSummary());
            default -> log.warn("B 站凭据探测（{}）未得出结论：{}", reason.getText(), status.getSummary());
        }
    }
}
