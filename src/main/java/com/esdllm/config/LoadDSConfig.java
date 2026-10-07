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

    /**
     * 配置键：B 站<b>凭据兜底探测</b>的间隔（小时），默认 <b>6</b>；<b>≤0 表示关闭</b>。
     *
     * <p><b>它兜的是什么</b>：Cookie 失效在本项目是<b>静默</b>的 —— 动态推送拉不到列表，
     * 但用户看到的现象只是"机器人突然不推了"。默认的探测形态是<b>事件驱动</b>
     * （推送一失败就顺手问一次服务端，见 {@code CredentialGuard#probe}），
     * 常态下零额外请求；可它有个盲区：<b>一整天没有动态可推 ⇒ 推送从不失败 ⇒ 永远不探</b>。
     * 本键就是这个盲区的兜底频率。
     *
     * <p>间隔之所以是"小时"级：一次探测打的是 {@code x/web-interface/nav}，
     * 而服务端认为已登录时库<b>还会再问一次 {@code cookie/info}</b> ⇒ 一次探测 = 2 个请求，
     * 且这条通道正是被风控盯着的那个域。凭据失效是个"以小时/天计"的状态，不值得高频问。
     *
     * <p>改动即时生效（每次兜底检查时读取），不需要重启。
     */
    public static final String KEY_BILI_CREDENTIAL_CHECK_HOURS = "biliCredentialCheckHours";

    /**
     * 配置键：视频链接解析是否<b>附带热评</b>。取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>为什么默认关</b>：热评不在 {@code view/detail} 的响应里（该响应的 {@code reply}
     * 字段实测恒回一条空壳，见 {@code BilibiliAnalysisImpl#fetchHotComments}），
     * 只能另开一次评论接口 ⇒ <b>单次解析请求数 1 → 2</b>。而线上正被
     * {@code feed/space} 的 {@code -412} 困扰（该风控对请求密度敏感），
     * 所以"默认不加请求、谁想要谁开"是既定纪律。
     *
     * <p>改动即时生效（每次解析时读取），不需要重启。
     */
    public static final String KEY_BILI_ANALYSIS_WITH_COMMENTS = "biliAnalysisWithComments";

    /**
     * 配置键：直播链接解析是否<b>附带主播信息</b>（粉丝数 / 粉丝牌名）。
     * 取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>代价</b>：{@code LiveRoom} 里没有粉丝数，只能另打一次
     * {@code LiveExtra#getMasterInfo} ⇒ <b>单次解析请求数 1 → 2</b>。
     * （好消息是<b>不需要</b>再调 {@code Live#getUid(roomId)} —— uid 本来就在
     * {@code getLiveRoom} 的响应里，见 {@code BilibiliAnalysisImpl#fetchMasterInfo}。）
     *
     * <p>改动即时生效（每次解析时读取），不需要重启。
     */
    public static final String KEY_BILI_LIVE_WITH_MASTER_INFO = "biliLiveWithMasterInfo";

    /**
     * 配置键：视频链接解析是否<b>附带 AI 摘要</b>（B 站官方「AI 视频总结」）。
     * 取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>为什么默认关</b>：摘要不在 {@code view/detail} 的响应里，要另打一次
     * {@code x/web-interface/view/conclusion/get} ⇒ <b>单次解析请求数 1 → 2</b>。
     *
     * <p>🔴 <b>它比另外两项多一道硬门槛：该端点要「WBI 签名 + 登录凭据」两样，缺一不可。</b>
     * 没有注入 Cookie 时必定 {@code -101}（未登录）—— 所以
     * {@code BilibiliAnalysisImpl#fetchAiSummary} 在<b>无 Cookie 时一个请求都不发</b>，
     * 这个开关开不开都一样。也就是说：<b>开了这个开关还必须配好 Cookie 才会生效</b>。
     *
     * <p>⚠️ 另外并非每个视频都有摘要（B 站只对部分视频生成），这是<b>正常情况不是错误</b>，
     * 代码里静默跳过、不报错。
     *
     * <p>改动即时生效（每次解析时读取），不需要重启。
     */
    public static final String KEY_BILI_ANALYSIS_WITH_SUMMARY = "biliAnalysisWithSummary";

    /**
     * 配置键：<b>ffmpeg 可执行文件路径</b>。留空 = 自动查找
     * （{@code ./bin/ffmpeg} → PATH，见 {@code FfmpegProvider}）。
     *
     * <p><b>什么时候需要它</b>：机器上装了多个 ffmpeg，或者装在了既不在 PATH、也不在
     * {@code ./bin/} 的地方（比如服务器上手动装的 {@code /opt/ffmpeg/ffmpeg}）。
     *
     * <p>⚠️ 给了它就<b>只用它</b>：这个路径跑不起来时<b>不会</b>退回后备（只打一条警告），
     * 免得出现"我明明指定了，它却偷偷用了另一个版本"。
     *
     * <p>⚠️ 该值<b>只在启动时解析一次</b>（避免每次调用都起进程探活），改动需重启生效。
     */
    public static final String KEY_BILI_DOWNLOAD_FFMPEG_PATH = "biliDownloadFfmpegPath";

    /**
     * 配置键：没有可用 ffmpeg 时，<b>是否自动从镜像下载</b>一个静态二进制。默认 <b>开</b>。
     *
     * <p><b>为什么默认开</b>（本项目其它开关一律默认关，这个是例外）：这条链路存在的全部意义
     * 就是让「下载 Release 的 jar 直接启动」成立 —— 默认关掉等于这个能力不存在。
     * 而它的代价是<b>一次性</b> 28MB 流量（下完落在 {@code ./bin/} 复用，不会反复下），
     * 且<b>不碰任何 B 站接口</b>，所以不受"默认不多发请求"那条纪律的约束。
     *
     * <p><b>什么时候该关</b>：内网/离线环境，或者不想让机器人自己往外下东西。
     * 关掉后下载类功能直接回"ffmpeg 未就绪"，其它功能完全不受影响。
     */
    public static final String KEY_BILI_DOWNLOAD_FFMPEG_AUTO_FETCH = "biliDownloadFfmpegAutoFetch";

    /**
     * 配置键：ffmpeg 的<b>下载地址</b>，支持 {@code {platform}} 占位符
     * （取值形如 {@code linux-x64} / {@code win32-x64}）。留空 = 用内置镜像（npmmirror）。
     *
     * <p><b>什么时候需要它</b>：镜像站挂了、或者要走自己的内网制品库。
     *
     * <p>⚠️ 用了自定义地址就<b>不再校验 SHA256</b> —— 内置摘要只对内置源有意义，
     * 换了源还对摘要只会"永远校验失败"。此时改为"解压成功 + 能跑 {@code -version}"。
     */
    public static final String KEY_BILI_DOWNLOAD_FFMPEG_URL = "biliDownloadFfmpegUrl";

    // ------------------------------------------------------------------ 录播（直播录制）

    // 录播各项的默认值集中放这里（配置键缺失时用）。
    // 放在 key 旁边而不是散在消费方：这些数决定"会不会把盘写满"，改的时候应该一眼看全。
    /** 默认录播目录（相对进程工作目录） */
    public static final String DEFAULT_RECORD_DIR = "record";
    /** 默认开播检测间隔（秒） */
    public static final int DEFAULT_RECORD_CHECK_SECONDS = 30;
    /** 默认单场录制时长上限（秒）= 6 小时 */
    public static final int DEFAULT_RECORD_MAX_SECONDS = 21600;
    /** 默认并发录制上限 */
    public static final int DEFAULT_RECORD_MAX_CONCURRENT = 2;
    /** 默认磁盘水位下限（MB）= 2 GB */
    public static final int DEFAULT_RECORD_DISK_MIN_FREE_MB = 2048;
    /** 默认压缩水位（MB）= 15 GB */
    public static final int DEFAULT_RECORD_COMPRESS_TOTAL_MB = 15360;
    /** 默认硬水位（MB）= 25 GB */
    public static final int DEFAULT_RECORD_HARD_TOTAL_MB = 25600;
    /** 默认压缩目标最大高度（像素） */
    public static final int DEFAULT_RECORD_COMPRESS_HEIGHT = 720;
    /** 默认 H.265 CRF */
    public static final int DEFAULT_RECORD_COMPRESS_CRF = 28;
    /** 默认 x265 preset */
    public static final String DEFAULT_RECORD_COMPRESS_PRESET = "fast";
    /** 默认录制清晰度 qn（原画） */
    public static final int DEFAULT_RECORD_QUALITY = 10000;
    /** 默认交付模式：auto（能推断出 NapCat 可达基址就走 URL，否则本地路径） */
    public static final String DEFAULT_RECORD_DELIVERY_MODE = "auto";
    /** 默认录播文件服务监听端口 */
    public static final int DEFAULT_RECORD_SERVE_PORT = 2335;
    /** 默认监听地址（0.0.0.0 = 所有网卡；可收紧到 docker 网桥地址） */
    public static final String DEFAULT_RECORD_SERVE_BIND = "0.0.0.0";
    /** 默认下载链接有效期（分钟） */
    public static final int DEFAULT_RECORD_LINK_TTL_MINUTES = 15;
    /** 默认下载链接可用次数 */
    public static final int DEFAULT_RECORD_LINK_MAX_USES = 5;
    /** 默认 base64 交付的体积上限（MB） */
    public static final int DEFAULT_RECORD_BASE64_MAX_MB = 24;

    /**
     * 配置键：<b>录播总闸</b>。取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>为什么默认关</b>：录播是**唯一会持续占用磁盘和带宽**的功能 ——
     * 一台主播开播就多一个 ffmpeg 进程 + 一条持续写入的文件，
     * 而这个项目跑在一台 4 核、几十 G 盘的机器上。默认关掉，
     * 想让谁录谁自己开，这与 P2-5/P2-6/P2-7 那批"默认不改变行为"的开关是同一条纪律。
     *
     * <p>关了之后：开播检测任务直接返回，<b>一个 B 站请求都不发</b>，
     * 手动录制命令回一句"录播未启用"。
     */
    public static final String KEY_BILI_RECORD_ENABLED = "biliRecordEnabled";

    /**
     * 配置键：录播目录，**相对进程工作目录**（与 {@code ./resources/}、{@code ./bin/} 同一口径）。
     * 默认 {@code record}。
     *
     * <p>目录结构：{@code <录播目录>/<roomId>/<fid>/}，每场录制一个子目录
     * （见 {@code LiveRecordFile} 的类注释："删记录 = 删目录"，不会留下孤儿文件）。
     */
    public static final String KEY_BILI_RECORD_DIR = "biliRecordDir";

    /**
     * 配置键：<b>开播检测间隔（秒）</b>，默认 {@code 30}。
     *
     * <p>这个值 = "主播开播到开始录制"的最大延迟。之所以敢 30 秒：
     * 它打的是直播间信息端点（{@code live.bilibili.com}），不是被风控盯着的动态域；
     * 且只在<b>录播订阅</b>的房间上打，量级 = 订阅数 / 30 秒。
     *
     * <p>⚠️ 调小它不会让录制更"完整"—— 直播流是持续的，晚 30 秒开始只是少录开头 30 秒，
     * 而请求量是按比例涨的。别为了"抢开头"把它调到几秒。
     */
    public static final String KEY_BILI_RECORD_CHECK_SECONDS = "biliRecordCheckSeconds";

    /**
     * 配置键：<b>单场录制时长上限（秒）</b>，默认 {@code 21600}（6 小时）。
     *
     * <p><b>它是兜底，不是常规停止条件</b> —— 常规停止是"主播下播"（流断 + 状态翻转）。
     * 这道上限治的是：下播检测因为某种原因没生效时，别让一个进程把盘写满。
     *
     * <p>⚠️ 到达上限会**按正常收工处理**（合并、登记），不是"失败"。
     */
    public static final String KEY_BILI_RECORD_MAX_SECONDS = "biliRecordMaxSeconds";

    /**
     * 配置键：<b>并发录制上限</b>，默认 {@code 2}。
     *
     * <p>同时录 N 路 = N 个 ffmpeg 进程 + N 倍带宽。录制的 ffmpeg 用的是 {@code -c copy}
     * （不转码，CPU 近似为 0），所以瓶颈在带宽与磁盘、不在 CPU；
     * 2 路是"同时开播两个主播"这种常见情形的默认值。
     */
    public static final String KEY_BILI_RECORD_MAX_CONCURRENT = "biliRecordMaxConcurrent";

    /**
     * 配置键：<b>磁盘水位下限（MB）</b>，默认 {@code 2048}。
     *
     * <p>低于它就拒绝开始新的录制（已经在录的不动）。这条是**录音写入前的最后一道闸**：
     * 容量巡检是按"已登记的文件"算的，而正在录的那一路还没登记，
     * 只靠巡检拦不住"一边录一边把盘写满"。
     */
    public static final String KEY_BILI_RECORD_DISK_MIN_FREE_MB = "biliRecordDiskMinFreeMb";

    /**
     * 配置键：<b>压缩水位（MB）</b>，默认 {@code 15360}（15 GB）。{@code ≤0} = 关闭压缩。
     *
     * <p>录播总占用超过它就<b>开始压缩最旧的、还没压缩过的</b>（保留标记的除外），
     * 压到水位以下为止。/ {@code LiveRecordServiceImpl#maintain()} 是唯一读它的地方。
     *
     * <p>⚠️ <b>压缩是纯 CPU 的 H.265 重编码，很慢</b>（这台机器 4 核、无硬件编码器，
     * 15 GB 可能要数小时），所以它是**串行后台任务**、不会影响录制与推送；
     * 但也正因为它慢，<b>水位之差（25GB - 15GB = 10GB）就是留给它的缓冲</b>。
     * 把两个值调得很接近，会出现"还没压完就到删除线"的抖动。
     */
    public static final String KEY_BILI_RECORD_COMPRESS_TOTAL_MB = "biliRecordCompressTotalMb";

    /**
     * 配置键：<b>硬水位（MB）</b>，默认 {@code 25600}（25 GB）。{@code ≤0} = 不自动删除。
     *
     * <p>录播总占用超过它就<b>从最早的开始删除</b>，
     * <b>跳过 {@code keep=1} 的</b>（"标记为不删除的除外"），删到水位以下为止。
     *
     * <p>⚠️ 如果**所有**可删的都被标记了保留，它会停下来并打告警、<b>不会硬删</b> ——
     * 这时候该做的是人工处理，而不是让机器人替用户决定"哪个珍藏可以扔"。
     */
    public static final String KEY_BILI_RECORD_HARD_TOTAL_MB = "biliRecordHardTotalMb";

    /**
     * 配置键：压缩后的<b>目标最大高度</b>（像素），默认 {@code 720}；{@code ≤0} = 不改分辨率。
     *
     * <p>与 CRF 一起决定压缩比。源是 1080p 时降到 720p 通常能再省三成，配合 H.265 整体减半左右。
     * 宽度按比例自适应（{@code scale=-2:h}），不会被拉变形。
     */
    public static final String KEY_BILI_RECORD_COMPRESS_HEIGHT = "biliRecordCompressHeight";

    /**
     * 配置键：H.265 的 <b>CRF</b>（恒定质量因子），默认 {@code 28}。
     *
     * <p>数值越大越糊、越小越清晰也越大。x265 的 28 大致相当于 x264 的 23（默认值），
     * 在录播这种"能看清就行"的场景是合适的；要更清晰就往 24 调。
     */
    public static final String KEY_BILI_RECORD_COMPRESS_CRF = "biliRecordCompressCrf";

    /**
     * 配置键：x265 的 <b>preset</b>，默认 {@code fast}。
     *
     * <p>⚠️ 这是"画质/体积"与"耗时"的旋钮，在 4 核机器上差别是**数倍**：
     * {@code medium} 比 {@code fast} 慢一倍多、只省几个百分点。默认 {@code fast} 是
     * 按这台机器的实际能力选的；想省时间可以降到 {@code veryfast}。
     */
    public static final String KEY_BILI_RECORD_COMPRESS_PRESET = "biliRecordCompressPreset";

    /**
     * 配置键：录制时请求的<b>清晰度 qn</b>，默认 {@code 10000}（原画）。
     *
     * <p>⚠️ 服务端可能静默降级（实测"要原画给高清"），实际拿到什么以录制时读到的
     * {@code current_qn} 为准，会记在录播文件行上、并在列表里如实展示。
     */
    public static final String KEY_BILI_RECORD_QUALITY = "biliRecordQuality";

    /**
     * 配置键：<b>NapCat 能从哪个基址下载录播</b>，形如 {@code http://172.17.0.1:2335}。
     *
     * <p>🔴 <b>这是"NapCat 和机器人不在一台机器上"时唯一可靠的开关</b>。
     * OneBot 的 {@code upload_group_file} 里那个 file 是<b>NapCat 自己去打开</b>的，
     * 所以本地路径在"NapCat 跑在 Docker 里"时必然失败 —— <b>线上就是这种情况</b>
     * （2026-10-04 实测：napcat 容器在默认 bridge 上，宿主机 docker0 = {@code 172.17.0.1}）。
     *
     * <p>留空 = 自动推断（优先 docker 网桥地址 → 出口网卡地址 → 回环）。
     * <b>推断结果会打进启动日志</b>，拿不准就直接看日志，或配死这个键。
     */
    public static final String KEY_BILI_RECORD_PUBLIC_BASE_URL = "biliRecordPublicBaseUrl";

    /**
     * 配置键：录播交付方式，取值 {@code auto}（默认）/ {@code local} / {@code url} / {@code base64}。
     *
     * <ul>
     *   <li>{@code auto}：能推断出可达基址走 {@code url}，否则退回 {@code local}；</li>
     *   <li>{@code local}：把本地路径直接交给 NapCat —— <b>仅当它与机器人共享文件系统时可用</b>；</li>
     *   <li>{@code url}：内置文件服务出 URL，NapCat 自己去下（跨容器/跨机时用这个）；</li>
     *   <li>{@code base64}：把文件内容内联在指令里 —— <b>零网络依赖</b>，代价是体积，
     *       超过 {@link #KEY_BILI_RECORD_BASE64_MAX_MB} 会被拒绝。</li>
     * </ul>
     *
     * <p>⚠️ {@code auto} 在两种拓扑下都能给出正确结果，<b>除非</b>NapCat 在另一台机器上
     * 且自动推断错了（那时会表现为"上传失败"）—— 这时显式配 {@link #KEY_BILI_RECORD_PUBLIC_BASE_URL}。
     */
    public static final String KEY_BILI_RECORD_DELIVERY_MODE = "biliRecordDeliveryMode";

    /**
     * 配置键：内置录播文件服务的<b>监听端口</b>，默认 {@code 2335}。
     *
     * <p>被占用时会自动退到随机端口（只是下载链接里的端口变了，功能不受影响）——
     * 但容器端口映射/防火墙是按固定端口配的，所以那种情况下建议把它改成一个空闲端口。
     */
    public static final String KEY_BILI_RECORD_SERVE_PORT = "biliRecordServePort";

    /**
     * 配置键：内置录播文件服务的<b>监听地址</b>，默认 {@code 0.0.0.0}。
     *
     * <p>⚠️ {@code 0.0.0.0} = 所有网卡，包括公网那一个。下载链接本身有随机 token + 时效 + 次数上限，
     * 但如果这台机器有公网 IP，建议收紧成 NapCat 真正需要的那张网卡
     * （例如容器场景下的 {@code 172.17.0.1}），或直接在防火墙上只放给容器网段。
     */
    public static final String KEY_BILI_RECORD_SERVE_BIND = "biliRecordServeBind";

    /**
     * 配置键：下载链接<b>有效期（分钟）</b>，默认 {@code 15}。过期的链接 410。
     *
     * <p>"发出去就不管"的下载链接等于永久公开，所以有它。
     */
    public static final String KEY_BILI_RECORD_LINK_TTL_MINUTES = "biliRecordLinkTtlMinutes";

    /**
     * 配置键：下载链接<b>可用次数</b>，默认 {@code 5}。
     *
     * <p>给几次余量是为了容忍断点重试（客户端可能重复发请求），但不能无限。
     */
    public static final String KEY_BILI_RECORD_LINK_MAX_USES = "biliRecordLinkMaxUses";

    /**
     * 配置键：{@code base64} 交付模式的体积上限（MB），默认 {@code 24}。
     *
     * <p>base64 会把文件放大 1/3 并<b>整个读进内存</b>，还要再塞进一条 WS 消息里
     * （两端各一份）⇒ 大文件用它只会把两个进程一起搞崩。超过就明确拒绝并提示改用 url 模式。
     */
    public static final String KEY_BILI_RECORD_BASE64_MAX_MB = "biliRecordBase64MaxMb";

    /**
     * 配置键：<b>录播网页</b>总闸。取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>它开的是什么</b>：一个网页（挂在 HTTP 服务上，默认 2233 端口），
     * 群成员用群里的链接打开就能<b>浏览 / 在线播放 / 下载</b>本群订阅的录播。
     * 群里 botadmin 及以上发「录播网页」拿到链接，转发给群成员即可。
     *
     * <p><b>为什么默认关</b>：这条功能与其它所有开关都不同 —— 它把本机磁盘上的文件
     * <b>暴露给浏览器</b>。虽然用的是群级随机令牌（可重置），但"要不要在网上开一个口子"
     * 该由人显式决定，不该是装完就有的默认状态。
     *
     * <p>关了之后：{@code /record/**} 一律 404（<b>不是</b> 403 —— 那等于告诉外面
     * "这里有个功能，只是你没权限"），「录播网页」命令回一句"未启用"。
     */
    public static final String KEY_BILI_RECORD_WEB_ENABLED = "biliRecordWebEnabled";

    /**
     * 配置键：录播网页的<b>对外基址</b>，形如 {@code http://example.com} 或
     * {@code http://1.2.3.4:2233}（可以带反代路径前缀）。
     *
     * <p>留空 = 自动推断：取"去往 NapCat 的出口网卡地址"（已排除 docker 网桥）+ 监听端口。
     * 推断结果会打进启动日志与「录播网页」命令的回显里。
     *
     * <p>🔴 <b>什么时候必须显式配</b>：机器有公网 IP 但走域名/反代、
     * 或者 HTTP 服务只在容器/内网可达而群成员要从外面打开 —— 这时自动推断出的是
     * <b>内网地址</b>，链接发给群成员会打不开。判断方法很直接：
     * 命令回的链接<b>你自己在手机上点一下</b>能不能打开。
     */
    public static final String KEY_BILI_RECORD_WEB_BASE_URL = "biliRecordWebBaseUrl";

    // ------------------------------------------------------------------ 反刷屏（AntiSpam）

    /** 默认滑窗（秒）：频率与去重都按这个时间窗统计 */
    public static final int DEFAULT_ANTI_SPAM_WINDOW_SECONDS = 60;
    /** 默认告警冷却（分钟）：同一群同一原因在这个时长内最多告警一次，避免机器人自己刷屏 */
    public static final int DEFAULT_ANTI_SPAM_ALERT_COOLDOWN_MINUTES = 5;
    /** 默认相同消息阈值（次）：同一内容在滑窗内出现这么多次才判"重复刷屏" */
    public static final int DEFAULT_ANTI_SPAM_DUP_THRESHOLD = 3;
    /** 默认动作级别：仅告警 */
    public static final String DEFAULT_ANTI_SPAM_ACTION = "alert";
    /** 默认单人阈值（{@code 类型:条数} 逗号分隔，{@code other} 为未列出类型的兜底） */
    public static final String DEFAULT_ANTI_SPAM_USER_LIMITS =
            "image:6,forward:2,video:3,record:5,share:3,face:8,text:12,command:5,other:15";
    /** 默认全群阈值 */
    public static final String DEFAULT_ANTI_SPAM_GROUP_LIMITS =
            "image:20,forward:5,video:10,record:15,share:8,face:30,text:40,command:15,other:50";

    /**
     * 配置键：<b>反刷屏总闸</b>。取值 {@code true/1/on/yes} 才开，<b>默认关</b>。
     *
     * <p><b>为什么默认关</b>：它会读<b>每一条</b>群消息并做统计，属于"装上就改变行为"的功能，
     * 与本项目其它所有开关同一条纪律 —— 想让谁监控谁自己开。
     * 关掉后检测钩子在入口直接返回，不做任何统计、一个告警都不发。
     *
     * <p>改动即时生效（每条消息现读），不需要重启。
     */
    public static final String KEY_ANTI_SPAM_ENABLED = "antiSpamEnabled";

    /**
     * 配置键：命中后的<b>处置级别</b>，取值 {@code alert}（默认）/ {@code recall} / {@code ban}。
     *
     * <ul>
     *   <li>{@code alert}：只私信机器人所有者，不动任何消息（最安全，建议先用这个跑一阵看误报）；</li>
     *   <li>{@code recall}：alert 之上再撤回那条触发消息 —— <b>要求机器人是群管理员</b>；</li>
     *   <li>{@code ban}：recall 之上再对发送者禁言 10 分钟 —— 同样要求群管理员，<b>误伤代价最大</b>。</li>
     * </ul>
     *
     * <p>⚠️ QQ 拦不住<b>已发出</b>的消息，所谓"拦截"只能事后撤回 / 禁言，这两个都要群管理权限。
     * 填别的值一律按 {@code alert} 处理（fail-safe）。
     */
    public static final String KEY_ANTI_SPAM_ACTION = "antiSpamAction";

    /**
     * 配置键：<b>统计滑窗（秒）</b>，默认 {@code 60}。
     *
     * <p>窗口越大越能容忍瞬时小爆发、但反应越慢；越小越灵敏、越容易把"正常的热闹"误报成刷屏。
     */
    public static final String KEY_ANTI_SPAM_WINDOW_SECONDS = "antiSpamWindowSeconds";

    /**
     * 配置键：<b>告警冷却（分钟）</b>，默认 {@code 5}。
     *
     * <p>同一群同一原因（类型/重复）在冷却期内只告警一次 —— 刷屏是持续的，没有这道冷却
     * 机器人会把所有者的私信箱刷爆，那就本末倒置了。
     */
    public static final String KEY_ANTI_SPAM_ALERT_COOLDOWN_MINUTES = "antiSpamAlertCooldownMinutes";

    /**
     * 配置键：<b>相同消息阈值（次）</b>，默认 {@code 3}。
     *
     * <p>同一内容（全群维度，归一化后取指纹）在滑窗内出现这么多次，判"重复刷屏"。
     */
    public static final String KEY_ANTI_SPAM_DUP_THRESHOLD = "antiSpamDupThreshold";

    /**
     * 配置键：<b>监控群白名单</b>，逗号分隔的群号；<b>留空 = 所有群</b>。
     *
     * <p>豁免（机器人自己 / 群主 / 群管理员 / {@code admin} 表白名单）与这个白名单是<b>两回事</b>：
     * 白名单决定"管哪些群"，豁免决定"群里哪些人不算"。
     */
    public static final String KEY_ANTI_SPAM_GROUPS = "antiSpamGroups";

    /**
     * 配置键：<b>单人频率阈值</b>，格式 {@code 类型:条数}、逗号分隔
     * （如 {@code image:6,forward:2,text:12}），未列出的类型用 {@code other} 兜底。
     *
     * <p>类型取值：{@code image / forward / video / record / share / face / text / command / other}。
     * 不配则用 {@link #DEFAULT_ANTI_SPAM_USER_LIMITS}。
     */
    public static final String KEY_ANTI_SPAM_USER_LIMITS = "antiSpamUserLimits";

    /**
     * 配置键：<b>全群频率阈值</b>，格式同 {@link #KEY_ANTI_SPAM_USER_LIMITS}。
     *
     * <p>防的是"多人一起轰炸 / 病毒式转发" —— 单看单人阈值拦不住这种。
     * 不配则用 {@link #DEFAULT_ANTI_SPAM_GROUP_LIMITS}。
     */
    public static final String KEY_ANTI_SPAM_GROUP_LIMITS = "antiSpamGroupLimits";

    // ==================== 出站发送熔断（SendGuard，防机器人自己刷屏） ====================
    // 与入站反刷屏（antiSpam*）的区别：那套防"别人刷"，这套防"自己刷"——
    // 任何推送循环 bug / 设计缺陷导致机器人向同一目标高频/重复发送时，
    // 在 ActionHandler.action 出口处熔断 60s 并私信告警所有者。
    // 保险丝定位 ⇒ 默认开（只在异常频率时动作，正常业务远低于阈值）。

    /** 默认值：发送滑窗（秒），群/私聊共用。 */
    public static final int DEFAULT_SEND_GUARD_WINDOW_SECONDS = 60;

    /** 默认值：同一群窗口内最多发送条数（标准档）。 */
    public static final int DEFAULT_SEND_GUARD_GROUP_LIMIT = 20;

    /** 默认值：同一私聊对象窗口内最多发送条数（标准档）。 */
    public static final int DEFAULT_SEND_GUARD_PRIVATE_LIMIT = 10;

    /** 默认值：同一目标窗口内完全相同内容达到该条数即熔断。 */
    public static final int DEFAULT_SEND_GUARD_DUP_THRESHOLD = 3;

    /** 默认值：熔断时长（秒），期间发往该目标的消息一律丢弃。 */
    public static final int DEFAULT_SEND_GUARD_CIRCUIT_SECONDS = 60;

    /** 默认值：同一目标熔断告警冷却（分钟）。 */
    public static final int DEFAULT_SEND_GUARD_ALERT_COOLDOWN_MINUTES = 5;

    /**
     * 配置键：<b>出站发送熔断总闸</b>。1 开 / 0 关，<b>缺省开</b>（保险丝常开）。
     *
     * <p>关闭后所有发送直通，不做任何统计。
     */
    public static final String KEY_SEND_GUARD_ENABLED = "sendGuardEnabled";

    /** 配置键：发送滑窗（秒）。不配用 {@link #DEFAULT_SEND_GUARD_WINDOW_SECONDS}。 */
    public static final String KEY_SEND_GUARD_WINDOW_SECONDS = "sendGuardWindowSeconds";

    /** 配置键：单群窗口内最大发送条数。不配用 {@link #DEFAULT_SEND_GUARD_GROUP_LIMIT}。 */
    public static final String KEY_SEND_GUARD_GROUP_LIMIT = "sendGuardGroupLimit";

    /** 配置键：单私聊窗口内最大发送条数。不配用 {@link #DEFAULT_SEND_GUARD_PRIVATE_LIMIT}。 */
    public static final String KEY_SEND_GUARD_PRIVATE_LIMIT = "sendGuardPrivateLimit";

    /** 配置键：相同内容熔断条数。不配用 {@link #DEFAULT_SEND_GUARD_DUP_THRESHOLD}。 */
    public static final String KEY_SEND_GUARD_DUP_THRESHOLD = "sendGuardDupThreshold";

    /** 配置键：熔断时长（秒）。不配用 {@link #DEFAULT_SEND_GUARD_CIRCUIT_SECONDS}。 */
    public static final String KEY_SEND_GUARD_CIRCUIT_SECONDS = "sendGuardCircuitSeconds";

    /** 配置键：熔断告警冷却（分钟）。不配用 {@link #DEFAULT_SEND_GUARD_ALERT_COOLDOWN_MINUTES}。 */
    public static final String KEY_SEND_GUARD_ALERT_COOLDOWN_MINUTES = "sendGuardAlertCooldownMinutes";


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
            long exists = adminService.count(new LambdaQueryWrapper<Admin>()
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
     * 读一个<b>布尔开关</b>。
     *
     * <p>判定是 <b>fail-closed</b> 的：<b>只有明确写成 {@code true / 1 / on / yes}
     * 才算开</b>，其余（键缺失、值空白、拼错成 {@code ture}、写成 {@code 0}/{@code false}）
     * 一律按关处理。理由是这个开关的作用是"多打一次 B 站接口"，
     * 而"配置写错反而把请求量放大"比"配置写错没生效"难查得多。
     *
     * <p>取值每次都从 {@link #configMap} 现读，所以改配置即时生效、不用重启。
     *
     * @param key 配置键（用本类里的 {@code KEY_*} 常量）
     * @return 是否开启
     */
    public boolean isEnabled(String key) {
        String raw = configMap.get(key);
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "on".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value);
    }

    /**
     * 与 {@link #isEnabled(String)} 同口径，但可以指定<b>键缺失时的默认值</b>。
     *
     * <p>唯一的差别是"没配"这一格的答案：那个是 fail-closed（缺失即关），
     * 这个允许「默认开」。<b>有值时两者判定完全一致</b>（只有 {@code true/1/on/yes} 算开）
     * —— 所以显式写 {@code 0} / {@code false} 照样能把它关掉，不会出现"默认开就关不掉"的怪事。
     *
     * <p>用途：像 {@link #KEY_BILI_DOWNLOAD_FFMPEG_AUTO_FETCH} 这种
     * "开了才是它本来该有的样子"的开关。
     *
     * @param key          配置键（用本类里的 {@code KEY_*} 常量）
     * @param defaultValue 键缺失或空白时的取值
     * @return 是否开启
     */
    public boolean isEnabled(String key, boolean defaultValue) {
        String raw = configMap.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return isEnabled(key);
    }

    /**
     * 读一个<b>整数配置</b>，缺失 / 空白 / 解析不出来时返回 {@code defaultValue}。
     *
     * <p>为什么把这个小工具收到这里，而不是让每个消费方各写一遍 try-catch：
     * 这类值（间隔秒数、水位 MB、CRF…）在录播一处就有十个，
     * 分散解析的话"非法值怎么办"就会被各写一遍、且很可能不一致（有的抛、有的吞）。
     * 这里的口径固定为<b>回落到默认值</b>：配置写错不该让功能炸掉，
     * 但也不能静默变成 0（那会让"关掉"和"写错了"长得一样）——
     * 所以调用方应当在 {@link #afterConfigChanged} 里回显一次取值。
     *
     * <p>取值每次都从 {@link #configMap} 现读，改配置即时生效、不用重启。
     *
     * @param key          配置键（用本类里的 {@code KEY_*} 常量）
     * @param defaultValue 缺失或非法时的取值
     * @return 解析出的整数，或默认值
     */
    public int intOf(String key, int defaultValue) {
        String raw = configMap.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 的值 [{}] 不是整数，按默认值 {} 处理", key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 读一个<b>字符串配置</b>：键缺失 / 空白都当作"没配"，返回 {@code defaultValue}。
     *
     * <p>与 {@code FfmpegProvider#stringOf} 同口径（那里是私有的、语义是"没配返回 null"，
     * 这里是"没配给默认值"），新增的录播代码统一用这个。
     */
    public String stringOf(String key, String defaultValue) {
        String raw = configMap.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
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
        } else if (KEY_BILI_CREDENTIAL_CHECK_HOURS.equals(key)) {
            // 间隔由 CredentialGuard 每次兜底检查时读取（与 biliDynamicSource 同一范式），
            // 这里只回显一次，方便确认"改对了、立即生效了"。
            log.info("B 站凭据兜底探测间隔已改为 {} 小时（0 或负数 = 关闭兜底，只保留推送失败时的事件驱动探测）",
                    configMap.get(key));
        } else if (KEY_BILI_ANALYSIS_WITH_COMMENTS.equals(key)) {
            log.info("视频解析附带热评已{}（开启后单次解析的 B 站请求数 1 → 2）",
                    isEnabled(key) ? "开启" : "关闭");
        } else if (KEY_BILI_LIVE_WITH_MASTER_INFO.equals(key)) {
            log.info("直播解析附带主播信息（粉丝数/粉丝牌）已{}（开启后单次解析的 B 站请求数 1 → 2）",
                    isEnabled(key) ? "开启" : "关闭");
        } else if (KEY_BILI_ANALYSIS_WITH_SUMMARY.equals(key)) {
            // ⚠️ 这项多一道依赖：端点硬要求登录，没 Cookie 时即使开着也不发请求
            boolean on = isEnabled(key);
            log.info("视频解析附带 AI 摘要已{}（开启后单次解析的 B 站请求数 1 → 2）；当前{}",
                    on ? "开启" : "关闭",
                    HttpPolicy.hasCookie() ? "已配置 Cookie，可生效" : "未配置 Cookie ⇒ 该项不会生效");
        } else if (KEY_BILI_DOWNLOAD_FFMPEG_AUTO_FETCH.equals(key)) {
            log.info("ffmpeg 自动获取已{}（关闭后：本机没有 ffmpeg 时不再自动下载，"
                            + "下载类功能不可用，其它功能不受影响）",
                    isEnabled(key, true) ? "开启" : "关闭");
        } else if (KEY_BILI_DOWNLOAD_FFMPEG_PATH.equals(key)) {
            log.info("ffmpeg 路径已设为 [{}]（⚠️ 该值只在启动时解析一次，重启后生效）", configMap.get(key));
        } else if (KEY_BILI_DOWNLOAD_FFMPEG_URL.equals(key)) {
            log.info("ffmpeg 下载地址已设为 [{}]（⚠️ 自定义地址不做 SHA256 校验，改为「解压 + 能跑」判定）",
                    configMap.get(key));
        } else if (KEY_BILI_RECORD_ENABLED.equals(key)) {
            boolean on = isEnabled(key);
            log.info("录播总闸已{}（开启后：录播订阅里的主播开播会自动录制，会持续占用磁盘与带宽；"
                    + "关闭时一个 B 站请求都不发）", on ? "开启" : "关闭");
            if (on) {
                // 开启时把决定"会不会把盘写满"的几个数念一遍，省掉"上线后忘了自己配的多少"
                log.info("录播水位：压缩 {}MB / 硬上限 {}MB / 磁盘下限 {}MB / 并发 {} / 单场上限 {}秒 / 目录 {}",
                        intOf(KEY_BILI_RECORD_COMPRESS_TOTAL_MB, DEFAULT_RECORD_COMPRESS_TOTAL_MB),
                        intOf(KEY_BILI_RECORD_HARD_TOTAL_MB, DEFAULT_RECORD_HARD_TOTAL_MB),
                        intOf(KEY_BILI_RECORD_DISK_MIN_FREE_MB, DEFAULT_RECORD_DISK_MIN_FREE_MB),
                        intOf(KEY_BILI_RECORD_MAX_CONCURRENT, DEFAULT_RECORD_MAX_CONCURRENT),
                        intOf(KEY_BILI_RECORD_MAX_SECONDS, DEFAULT_RECORD_MAX_SECONDS),
                        stringOf(KEY_BILI_RECORD_DIR, DEFAULT_RECORD_DIR));
            }
        } else if (KEY_BILI_RECORD_COMPRESS_TOTAL_MB.equals(key)
                || KEY_BILI_RECORD_HARD_TOTAL_MB.equals(key)
                || KEY_BILI_RECORD_DISK_MIN_FREE_MB.equals(key)) {
            log.info("录播水位已改：{} = {}MB（压缩水位 ≤0 = 关闭压缩；硬水位 ≤0 = 不自动删除）",
                    key, configMap.get(key));
        } else if (KEY_BILI_RECORD_COMPRESS_HEIGHT.equals(key)
                || KEY_BILI_RECORD_COMPRESS_CRF.equals(key)
                || KEY_BILI_RECORD_COMPRESS_PRESET.equals(key)) {
            log.info("录播压缩参数已改：{} = {}（⚠️ preset 在 4 核机上耗时差别数倍，fast 与 medium 之间能差一倍多）",
                    key, configMap.get(key));
        } else if (KEY_BILI_RECORD_DIR.equals(key)) {
            log.info("录播目录已设为 [{}]（⚠️ 相对进程工作目录，与 ./resources/ 同一口径；"
                    + "改它不会搬迁已有记录指向的文件，请自行处理）", configMap.get(key));
        } else if (KEY_BILI_RECORD_PUBLIC_BASE_URL.equals(key)) {
            log.info("录播下载基址已设为 [{}]（NapCat 会从这个地址拉文件；"
                    + "NapCat 在容器/别的机器上时这一项最关键）", configMap.get(key));
        } else if (KEY_BILI_RECORD_DELIVERY_MODE.equals(key)) {
            log.info("录播交付方式已设为 [{}]（auto/能连就走URL、local/本地路径、url/走内置文件服务、base64/内联）",
                    configMap.get(key));
        } else if (KEY_BILI_RECORD_SERVE_PORT.equals(key) || KEY_BILI_RECORD_SERVE_BIND.equals(key)) {
            log.info("录播文件服务监听已改：{} = {}（⚠️ 该值只在启动时生效，需重启）",
                    key, configMap.get(key));
        } else if (KEY_BILI_RECORD_LINK_TTL_MINUTES.equals(key)
                || KEY_BILI_RECORD_LINK_MAX_USES.equals(key)
                || KEY_BILI_RECORD_BASE64_MAX_MB.equals(key)) {
            log.info("录播下载链接参数已改：{} = {}", key, configMap.get(key));
        } else if (KEY_BILI_RECORD_WEB_ENABLED.equals(key)) {
            boolean on = isEnabled(key);
            log.info("录播网页已{}（开启后：拿到链接的人都能浏览/播放/下载本群订阅的录播；"
                            + "链接可被转发，泄了就重置）",
                    on ? "开启" : "关闭");
            if (on) {
                log.info("⚠️ 网页是挂在 HTTP 服务端口上的，确保它对群成员可达；"
                        + "推断不出对外基址时用 {} 显式指定（例如 http://你的域名 或 http://1.2.3.4:端口）",
                        KEY_BILI_RECORD_WEB_BASE_URL);
            }
        } else if (KEY_BILI_RECORD_WEB_BASE_URL.equals(key)) {
            log.info("录播网页基址已设为 [{}]（群里「录播网页」命令发的链接会用它开头）",
                    configMap.get(key));
        } else if (KEY_ANTI_SPAM_ENABLED.equals(key)) {
            log.info("反刷屏总闸已{}（开启后：每条群消息都会做频率/重复统计，命中按 antiSpamAction 处置；"
                    + "关闭时入口直接返回、不做任何统计）", isEnabled(key) ? "开启" : "关闭");
        } else if (KEY_ANTI_SPAM_ACTION.equals(key)) {
            log.info("反刷屏处置级别已设为 [{}]（alert=仅告警 / recall=+撤回 / ban=+禁言，后两者要求机器人是群管理员）",
                    configMap.get(key));
        } else if (KEY_ANTI_SPAM_WINDOW_SECONDS.equals(key) || KEY_ANTI_SPAM_DUP_THRESHOLD.equals(key)
                || KEY_ANTI_SPAM_ALERT_COOLDOWN_MINUTES.equals(key) || KEY_ANTI_SPAM_GROUPS.equals(key)
                || KEY_ANTI_SPAM_USER_LIMITS.equals(key) || KEY_ANTI_SPAM_GROUP_LIMITS.equals(key)) {
            log.info("反刷屏参数已改：{} = {}", key, configMap.get(key));
        } else if (KEY_SEND_GUARD_ENABLED.equals(key)) {
            log.info("出站发送熔断总闸已{}（开启后：同一目标 60s 内超频发信或重复内容将被丢弃并熔断 60s，"
                    + "熔断时私信告警所有者；关闭时全部发送直通）",
                    isEnabled(key, true) ? "开启" : "关闭");
        } else if (KEY_SEND_GUARD_WINDOW_SECONDS.equals(key) || KEY_SEND_GUARD_GROUP_LIMIT.equals(key)
                || KEY_SEND_GUARD_PRIVATE_LIMIT.equals(key) || KEY_SEND_GUARD_DUP_THRESHOLD.equals(key)
                || KEY_SEND_GUARD_CIRCUIT_SECONDS.equals(key) || KEY_SEND_GUARD_ALERT_COOLDOWN_MINUTES.equals(key)) {
            log.info("出站发送熔断参数已改：{} = {}", key, configMap.get(key));
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
