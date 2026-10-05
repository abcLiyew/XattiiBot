package com.esdllm.service;

import com.esdllm.config.LoadDSConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/**
 * <b>ffmpeg 进程封装</b> —— 录播这条链路上所有"起一个 ffmpeg 干活"的地方都走这里。
 *
 * <h2>为什么单独一个类</h2>
 *
 * 录制、合并分片、转 mp4、压缩，是四条不同的 ffmpeg 命令，但它们有一条<b>完全一样</b>的
 * "进程纪律"，而这条纪律恰恰是最容易写出静默故障的地方：
 *
 * <ul>
 *   <li>🔴 <b>必须把子进程输出读走</b>（这里用 {@code Redirect.appendTo(日志文件)}）。
 *       不读的话，ffmpeg 往管道里写满 64KB 就会阻塞，{@code waitFor} 随即永久挂住 ——
 *       这是 {@code ProcessBuilder} 最经典的坑，而且是"偶尔才发生"，最难查。
 *       把输出重定向到文件而不是读进内存，是因为录制的 ffmpeg 要跑几小时、
 *       日志无关紧要但要留痕（出问题时能看它到底为什么退出）。</li>
 *   <li>🔴 <b>超时必须真的杀死进程</b>（{@code destroyForcibly}），不能只是"不再等它"。
 *       否则一个卡住的 ffmpeg 会一直占着流和文件句柄。</li>
 *   <li>🔴 <b>逐参数传 List，绝不拼 shell 串</b>：直播 URL 里带 {@code &}、{@code ?}，
 *       拼串过 shell 会被截断/加引号，而且等于把 URL 当代码执行。</li>
 *   <li>🔴 <b>必须加 {@code -nostdin}</b>：常驻服务里 ffmpeg 的 stdin 是个不确定的终端，
 *       它会读它（比如问"要不要覆盖"），在服务里就是直接卡死。</li>
 * </ul>
 *
 * <h2>命令构造也放这里</h2>
 *
 * 四条命令的参数值得写在一处看全 —— 尤其录制那条的 {@code -headers}/{@code -reconnect}
 * 必须出现在 {@code -i} <b>之前</b>（它们是输入侧的选项），挪到后面就是静默不生效。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@Component
public class LiveRecorder {

    /**
     * 录制时带的 UA。<b>不是为了绕什么</b>，而是 B 站 CDN 对空 UA 的请求偶发不友好，
     * 带上一个浏览器 UA 是零成本的"防未来变更"。
     */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    /**
     * HTTP 读写超时（微秒，ffmpeg 的 http 协议单位就是微秒）：15 秒。
     *
     * <p><b>它治的是"流卡住不报错"</b>：网络黑洞时 TCP 连接可以一直"活着"却没有任何数据，
     * ffmpeg 会安静地等下去 —— 而录播这边表现为"文件大小不涨、进程不退，
     * 直到单场上限才收工，录出一个几小时的黑屏"。有它就会超时退出，
     * 交由上层的"重连续录"逻辑处理。
     */
    private static final String RW_TIMEOUT_MICROS = "15000000";

    /**
     * 等待进程退出时，在"时长上限"之外额外给的宽限（秒）。
     *
     * <p>ffmpeg 到了 {@code -t} 之后还要 flush、写文件尾，不会瞬间退出。
     * 这段时间不算超时，否则每次正常收工都会被强杀、留下一个没写完的文件。
     */
    private static final int EXIT_GRACE_SECONDS = 120;

    @Resource
    private LoadDSConfig loadDSConfig;

    /**
     * 一次 ffmpeg 执行的结果。
     *
     * @param exitCode 退出码（{@code -1} = 被强杀 / 拿不到退出码）
     * @param timedOut 是否因为超时被强杀（区分"到点收工"与"卡死"）
     * @param bytes    产物字节数（文件不存在时为 0）
     */
    public record Result(int exitCode, boolean timedOut, long bytes) {

        /** 退出码 0（正常结束）。⚠️ 主播下播导致流断，ffmpeg 也是 0 —— 见 LiveRecordServiceImpl 的说明 */
        public boolean ok() {
            return exitCode == 0;
        }
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 跑一条 ffmpeg 命令并等它结束。
     *
     * @param command   完整命令行（<b>已含可执行文件路径</b>，见本类的各个 {@code xxxCommand}）
     * @param logFile   ffmpeg 的 stdout+stderr 落点（<b>必须给</b>，见类注释"必须把输出读走"）
     * @param output    产物路径，只用于回读体积；可为 {@code null}
     * @param timeoutSeconds 墙钟超时（秒）；≤0 表示不设超时（仅用于确定会很快结束的命令）
     * @param onProcess 进程启动后的回调，<b>用来拿到句柄以便外部取消</b>；可为 {@code null}
     * @return 执行结果
     */
    public Result run(List<String> command, Path logFile, Path output,
                      long timeoutSeconds, Consumer<Process> onProcess) {
        Process process = null;
        boolean timedOut = false;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // ★ 合并 stderr 到 stdout，再一起落到文件 —— 两个都要，否则 ffmpeg 的报错
            //   （全在 stderr）就丢了，而"为什么没录上"的答案全在那里。
            builder.redirectErrorStream(true);
            if (logFile != null) {
                Path parent = logFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            } else {
                // 没有日志文件时也必须给个落点，绝不能让管道悬着（不然就是类注释里那个死锁）
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }

            long started = System.currentTimeMillis();
            process = builder.start();
            if (onProcess != null) {
                onProcess.accept(process);
            }

            long deadline = timeoutSeconds > 0
                    ? started + (timeoutSeconds + EXIT_GRACE_SECONDS) * 1000L
                    : Long.MAX_VALUE;
            // 每秒醒一次而不是 waitFor(很久)：这样"外部取消"与"线程中断"都能在 1 秒内被响应
            while (process.isAlive()) {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    log.info("录制线程被中断，强杀 ffmpeg（pid={}）", process.pid());
                    process.destroyForcibly();
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true;
                    log.warn("ffmpeg 超过墙钟超时（{}秒）仍未退出，强杀（pid={}）", timeoutSeconds, process.pid());
                    process.destroyForcibly();
                    break;
                }
            }
            // 强杀之后还要再等一小会，否则 exitValue() 会抛 IllegalThreadStateException
            process.waitFor(30, TimeUnit.SECONDS);

            int exit = process.isAlive() ? -1 : process.exitValue();
            long bytes = sizeOf(output);
            log.debug("ffmpeg 结束：exit={}, 超时={}, 产物={} 字节, 耗时 {}ms",
                    exit, timedOut, bytes, System.currentTimeMillis() - started);
            return new Result(exit, timedOut, bytes);
        } catch (IOException e) {
            log.warn("启动 ffmpeg 失败（命令第一项是 [{}]）：{}",
                    command.isEmpty() ? "<空>" : command.get(0), e.toString());
            return new Result(-1, false, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(-1, false, 0);
        }
    }

    private static long sizeOf(Path file) {
        if (file == null) {
            return 0;
        }
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 命令构造

    /**
     * <b>录制命令</b>：把一条直播流原样拉下来存成 flv。
     *
     * <pre>
     * ffmpeg -hide_banner -loglevel warning -nostdin -y
     *        -headers "Referer: https://live.bilibili.com/\r\n" -user_agent UA
     *        -rw_timeout 15000000 -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5
     *        -i &lt;url&gt; -t &lt;limit&gt; -c copy -f flv &lt;out&gt;
     * </pre>
     *
     * <p>几个刻意的选择：
     * <ul>
     *   <li>{@code -c copy}：<b>不重编码</b>。录制几千小时消耗的 CPU 近似为 0，瓶颈只在带宽与磁盘。
     *       它也是"能录原画"的前提 —— 转码反而会把原画降级。</li>
     *   <li>{@code -f flv}：<b>直播录制的正确容器</b>。flv 是流式可写、随时断电都能播；
     *       mp4 的索引在文件尾，录到一半被杀就是一个打不开的坏文件 ——
     *       所以"要 mp4"这件事放在**取用的时候**做（见 {@link #remuxMp4Command}）。</li>
     *   <li>{@code -headers} / {@code -user_agent} / {@code -rw_timeout} / {@code -reconnect}
     *       全部在 {@code -i} <b>之前</b>：它们是输入侧选项，写到后面不生效（且不报错）。</li>
     *   <li>{@code -t}：单场硬上限。它是<b>兜底</b>，常规停止靠"主播下播"。</li>
     * </ul>
     *
     * @param ffmpeg       ffmpeg 可执行文件
     * @param url          直播流地址（含时效签名，<b>用完即弃，不要缓存</b>）
     * @param out          输出 flv 路径
     * @param limitSeconds 本段最长录制秒数（上层按"单场上限 - 已录"算出来）
     */
    public List<String> recordCommand(String ffmpeg, String url, Path out, long limitSeconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        // warning 而不是 error：既要安静，又想留下"重连了几次""流断了"这类线索
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-nostdin");
        cmd.add("-y");
        // —— 以下都是输入侧选项，必须在 -i 之前 ——
        cmd.add("-headers");
        // ffmpeg 的 -headers 用 \r\n 分隔多行，且**必须自己带**这个换行（它不会替你补）
        cmd.add("Referer: https://live.bilibili.com/\r\n");
        cmd.add("-user_agent");
        cmd.add(UA);
        cmd.add("-rw_timeout");
        cmd.add(RW_TIMEOUT_MICROS);
        // 同一进程内的轻量重连（网络抖动）；地址过期这类"真断了"由外层重新取址处理
        cmd.add("-reconnect");
        cmd.add("1");
        cmd.add("-reconnect_streamed");
        cmd.add("1");
        cmd.add("-reconnect_delay_max");
        cmd.add("5");
        cmd.add("-i");
        cmd.add(url);
        cmd.add("-t");
        cmd.add(String.valueOf(Math.max(1, limitSeconds)));
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("flv");
        cmd.add(out.toString());
        return cmd;
    }

    /**
     * <b>合并分片</b>：把同一场录制的多个 flv 分片按顺序拼成一个。
     *
     * <p>为什么会有分片：B 站的直播地址带 {@code expires} 时效（几小时），
     * 超过时效 ffmpeg 一定退出，必须<b>重新取址续录</b> —— 而"续"出来的第一段是独立文件，
     * 所以一场长直播天然会变成 N 个分片。这里把它们拼回去。
     *
     * <p>{@code -c copy} 拼接：分片是同一路流的连续片段，编码参数一致，直接 copy 即可。
     * {@code -safe 0} 是为了让 list 文件里的<b>绝对路径</b>被接受（默认只允许相对路径）。
     *
     * @param ffmpeg   ffmpeg 可执行文件
     * @param listFile concat 清单文件（每行 {@code file '<路径>'}，由调用方生成）
     * @param out      输出 flv
     */
    public List<String> mergeCommand(String ffmpeg, Path listFile, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-nostdin");
        cmd.add("-y");
        // concat 分离器会为每个输入做时间戳偏移，分片各自从 0 开始也没问题
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-safe");
        cmd.add("0");
        cmd.add("-i");
        cmd.add(listFile.toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("flv");
        cmd.add(out.toString());
        return cmd;
    }

    /**
     * <b>转 mp4（重封装）</b>：用户要取用录播时，把 flv 换成 mp4。
     *
     * <p>{@code -c copy} —— <b>不重编码，秒级完成</b>。这不是"转码"，只是换容器：
     * flv 里本来就是 H.264 + AAC，mp4 完全装得下，所以画质一比特都不损失。
     * 之所以要做：mp4 的兼容性远好于 flv（QQ、微信、Windows 自带播放器都认）。
     *
     * <p>{@code -movflags +faststart} 把索引（moov）挪到文件头 ——
     * 少了它，接收方要下完整个文件才能开始播。
     *
     * @param ffmpeg ffmpeg 可执行文件
     * @param in     源 flv
     * @param out    目标 mp4
     */
    public List<String> remuxMp4Command(String ffmpeg, Path in, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(in.toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(out.toString());
        return cmd;
    }

    /**
     * <b>压缩命令</b>：降分辨率 + H.265 重编码，用来把超出水位的录播腾出空间。
     *
     * <pre>
     * ffmpeg -i in.flv -vf "scale=-2:min(&lt;h&gt;\,ih)" -c:v libx265 -preset &lt;p&gt; -crf &lt;crf&gt;
     *        -tag:v hvc1 -c:a aac -b:a 128k -movflags +faststart out.mp4
     * </pre>
     *
     * <p><b>这是本项目里唯一一条"慢"命令</b>：纯 CPU 重编码，4 核机器上 15 GB 可能要数小时。
     * 所以它必须串行、后台、不阻塞任何别的东西（见 {@code LiveRecordServiceImpl} 的压缩线程）。
     *
     * <p>细节：
     * <ul>
     *   <li>{@code scale=-2:min(h,ih)}：<b>只降不升</b>。直接写 {@code scale=-2:720} 会把
     *       480p 的源<b>放大</b>到 720p —— 体积反而涨，这就闹笑话了。
     *       逗号在 filter 语法里是分隔符，所以这里要写成 {@code \,}（不放引号，因为参数是直接
     *       传给进程的，引号会变成字面量）。</li>
     *   <li>{@code -tag:v hvc1}：HEVC 在 mp4 里的两种标记，{@code hvc1} 比默认的
     *       {@code hev1} 被更多播放器认（尤其 Apple 系）。零成本，就加上。</li>
     *   <li>音频统一转 AAC 128k：音频转码开销可以忽略，换来的是一致性
     *       （不必赌源里一定是 AAC）。</li>
     * </ul>
     *
     * @param ffmpeg ffmpeg 可执行文件
     * @param in     源文件（flv）
     * @param out    目标 mp4
     * @param height 目标最大高度（≤0 = 不改分辨率，此时不加 {@code -vf}）
     * @param crf    H.265 的 CRF
     * @param preset x265 preset（越慢越省体积）
     */
    public List<String> compressCommand(String ffmpeg, Path in, Path out,
                                        int height, int crf, String preset) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(in.toString());
        if (height > 0) {
            cmd.add("-vf");
            // ⚠️ 这个 \, 是 filter 语法的转义，不是 Java 转义 —— 别"顺手"删掉
            cmd.add("scale=-2:min(" + height + "\\,ih)");
        }
        cmd.add("-c:v");
        cmd.add("libx265");
        cmd.add("-preset");
        cmd.add(preset);
        cmd.add("-crf");
        cmd.add(String.valueOf(crf));
        cmd.add("-tag:v");
        cmd.add("hvc1");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(out.toString());
        return cmd;
    }

    /**
     * 探测媒体时长。用 {@code ffprobe} 读容器里的 duration。
     *
     * <p>⚠️ <b>返回毫秒</b>（方法名原来叫 {@code probeDurationSeconds}、javadoc 也写"秒数"，
     * 但实现里乘了 1000 —— 名字与单位对不上，将来第二个调用方一定会按名字用、少乘或多乘 1000 倍，
     * 那种错在"录了 3 小时"和"录了 12 天"之间，很难从现象反推。故按实际单位改名。）
     *
     * <p>⚠️ <b>为什么不用墙钟时间当"录制时长"</b>：一场录制里包含取址重试、断流重连的等待，
     * 墙钟时间会比实际录到的内容长；而且 {@code -t} 到点后 ffmpeg 还要 flush。
     * 时长是要展示给用户看的东西，宁可用容器里的真实值。
     *
     * @param ffprobe ffprobe 可执行文件（与 ffmpeg 同目录）
     * @param file    媒体文件
     * @return <b>毫秒</b>；读不出来返回 0（不抛）
     */
    public long probeDurationMillis(String ffprobe, Path file) {
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobe, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString())
                    .redirectErrorStream(true)
                    .start();
            // 这条命令输出很小，直接读完即可（同样不能挂着不读）
            String out = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
                double seconds = Double.parseDouble(out);
                return Math.round(seconds * 1000);
            }
            process.destroyForcibly();
            return 0;
        } catch (Exception e) {
            // ffprobe 不一定会随 ffmpeg 一起装（本项目下载的静态包里有，但 PATH 里那份不一定）
            // ⇒ 读不到就是 0，由调用方回落到墙钟估算，不让它成为"录制失败"的理由
            log.debug("ffprobe 读时长失败（回落到墙钟估算）：{}", e.toString());
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return 0;
        }
    }

    /**
     * ffmpeg 可执行文件路径 → ffprobe 路径（同目录同名规则）。
     *
     * <p>找不到就返回 {@code null}，调用方会跳过时长探测。
     */
    public String ffprobeOf(String ffmpeg) {
        Path p = Path.of(ffmpeg);
        String name = p.getFileName() == null ? "ffmpeg" : p.getFileName().toString();
        String probeName = name.replace("ffmpeg", "ffprobe");
        Path probe = p.getParent() == null ? Path.of(probeName) : p.getParent().resolve(probeName);
        if (probeName.equals(name)) {
            // 传进来的本来就不是 ffmpeg*（比如叫别的名字）⇒ 没法推，交给 PATH 兜
            probe = Path.of("ffprobe");
        }
        return Files.isExecutable(probe) ? probe.toString() : "ffprobe";
    }
}
