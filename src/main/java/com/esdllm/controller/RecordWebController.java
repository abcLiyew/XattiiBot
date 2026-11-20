package com.esdllm.controller;

import com.esdllm.common.HttpFileSupport;
import com.esdllm.model.LiveRecordFile;
import com.esdllm.model.LiveRecordSub;
import com.esdllm.service.LiveRecordService;
import com.esdllm.service.RecordWebAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「录播网页」—— 让<b>群成员的浏览器</b>直接看录播。
 *
 * <h2>为什么是 Spring MVC 而不是接着用 JDK 的 HttpServer</h2>
 * 内置的 {@code RecordFileServer}（给 NapCat 下载文件用）只出字节流，够用；
 * 而这条功能要出一个 <b>HTML 页面 + JSON + 视频流</b>三样东西，还要被浏览器直接访问
 * （浏览器会自己发 {@code Range}、{@code OPTIONS}、{@code favicon.ico}…），
 * 那是 MVC 的活儿。依赖其实一直都在：{@code spring-webmvc} 与 {@code tomcat-embed-core}
 * 由 {@code com.mikuac:shiro} 传递带来（启动日志里的
 * {@code ConfigServletWebServerApplicationContext} / {@code Tomcat started on port} 就是证据），
 * 所以这里是"用起来"，不是"新引一套"。
 *
 * <h2>地址与端口</h2>
 * 走本进程已有的 HTTP 服务（{@code server.port}，线上 2233），<b>不另开端口</b>：
 * 再开一个口子就得再配一次防火墙、再做一次可达性判断，而这两个问题的答案和 HTTP 服务是同一个。
 *
 * <h2>谁看得到什么（这是本类最要紧的一节）</h2>
 * <ul>
 *   <li><b>群令牌</b>（{@code group_id} 有值）→ 只能看<b>本群订阅过的房间</b>录出来的场次。
 *       路径里的令牌只决定"你是哪个群"，而"哪几场能看"由
 *       {@link LiveRecordService#roomsOfGroup} 现算 —— <b>每一个</b>取文件的接口都要再过一次
 *       {@link #inScope}，因为 {@code fid} 是自增的、可以被猜；只校验令牌不校验归属，
 *       等于把全站的录播挂在一个可枚举的 id 上。</li>
 *   <li><b>全局管理码</b>（{@code group_id} 为空）→ 看全部，并且能改保留 / 删录播。</li>
 * </ul>
 * <p>管理动作额外要一次 {@link RecordWebAuth#MANAGE_HEADER} 头（值 = 管理码）：
 * 光有一个群令牌不够 —— 群令牌是<b>给整个群转发</b>的，它不能顺带把删除权也发出去。
 *
 * <h2>播放</h2>
 * 浏览器放不了 flv，所以播放前先按需转 mp4（{@code -c copy} 重封装，秒级无损，
 * 见 {@link LiveRecordService#playableFile}）。前端先调 {@code /prepare/{fid}} 拿结果，
 * 成功之后才把地址交给 {@code <video>} —— 否则用户只会看到一个不动转圈的播放器，
 * 而真正的失败原因（没文件 / 转码失败）全被吞掉。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
@RestController
@RequestMapping("/record")
public class RecordWebController {

    /** 一页多少场 */
    private static final int PAGE_SIZE = 12;

    /**
     * 页面文件名（classpath 下）。
     *
     * <p>⚠️ 刻意<b>不</b>放 {@code static/} 下：那会被 Spring Boot 的静态资源处理器
     * 直接对外暴露（{@code /record-web.html} 不带令牌也能拿到）。放在这个自有目录里，
     * 唯一的出口就是下面这个带令牌校验的 handler。
     */
    private static final String PAGE_RESOURCE = "record/record-web.html";

    @Resource
    private RecordWebAuth auth;
    @Resource
    private LiveRecordService records;

    /**
     * 只为了写错误 JSON。
     *
     * <p>刻意<b>自己 new 一个</b>而不是注入 Spring 那个：这里要序列化的就是几个 String/long，
     * 犯不上依赖自动装配（少一个"哪天 JacksonAutoConfiguration 因为什么原因没生效"的失败面）。
     * 用的是 {@code ObjectMapper} 的默认配置，与 Spring 那份的差别在本类用不到。
     */
    private final ObjectMapper json = new ObjectMapper();

    /** 页面 HTML，读一次缓存住 */
    private volatile String cachedPage;

    // ================================================================== 页面

    /**
     * 网页本体：{@code GET /record/{token}}。
     *
     * <p>页面本身是<b>不含任何录播内容</b>的空壳（内容全部靠 JS 再调 {@code /api/...} 拉），
     * 所以它能被缓存、能公开拿；真正的门在那些 API 上。
     */
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        if (!auth.enabled() || auth.resolve(token) == null) {
            return notFound();
        }
        String html = page();
        if (html == null) {
            return ResponseEntity.status(500)
                    .contentType(TEXT_HTML_UTF8)
                    .body("<h3>页面文件缺失</h3><p>jar 里找不到 " + PAGE_RESOURCE + "</p>");
        }
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                // 链接里的令牌是凭据，别让它进搜索引擎、也别让中间层缓存住
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .cacheControl(CacheControl.noStore())
                .body(html);
    }

    // ================================================================== 列表

    /** {@code GET /record/api/{token}/files?page=N} → 本页场次 + 统计。 */
    @GetMapping("/api/{token}/files")
    public ResponseEntity<?> files(@PathVariable String token,
                                   @RequestParam(defaultValue = "1") int page) {
        RecordWebAuth.View view = resolveView(token);
        if (view == null) {
            return notFound();
        }
        Collection<Long> scope = scopeOf(view);
        int pageNo = Math.max(1, Math.min(page, 9999));
        long total = records.countFilesOfRooms(scope);
        List<LiveRecordFile> rows = records.listFilesOfRooms(pageNo, PAGE_SIZE, scope);

        List<Map<String, Object>> items = new ArrayList<>();
        // 全群视图（管理码）下，"这一场是谁的"是必须回答的问题 —— 否则 8 张卡片长得一模一样，
        // 想删某个群的东西只能靠记忆。只在 admin 视图构建这张表：群视图里所有场次本来就属于本群，
        // 多查一次纯属浪费。
        Map<Long, List<Long>> groupsOfRoom = view.admin() ? groupsOfRoom() : Map.of();
        for (LiveRecordFile row : rows) {
            items.add(item(row, groupsOfRoom));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("view", view.kind());
        body.put("admin", view.admin());
        body.put("groupId", view.groupId());
        body.put("page", pageNo);
        body.put("pages", Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        body.put("total", total);
        body.put("usedBytes", records.usedBytesOfRooms(scope));
        body.put("pageSize", PAGE_SIZE);
        body.put("items", items);
        if (!view.admin() && records.roomsOfGroup(view.groupId()).isEmpty()) {
            body.put("hint", "本群还没有订阅过任何直播间。让群里会操作的人在群里发「录播订阅 房间号」就会开始录，"
                    + "录出来的东西会出现在这里。");
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 房间 → 订阅它的群列表（只给全群视图用）。
     *
     * <p>一次查全表而不是"每行查一次"：订阅总数是几十条量级，一页 8 行就是 8 次查询，
     * 而管理台每翻一页都要重来一遍。这里是典型的 N+1，顺手就避掉了。
     *
     * <p>同一个房间被多个群订阅时是<b>多行</b>（录制仍只一路），所以值是个列表。
     */
    private Map<Long, List<Long>> groupsOfRoom() {
        Map<Long, List<Long>> map = new LinkedHashMap<>();
        try {
            for (LiveRecordSub sub : records.listSubs()) {
                if (sub.getRoomId() == null || sub.getGroupId() == null) {
                    continue;               // 全局订阅（group_id 为空）不归属任何群，前端显示"全局"
                }
                map.computeIfAbsent(sub.getRoomId(), k -> new ArrayList<>()).add(sub.getGroupId());
            }
        } catch (Throwable t) {
            // 这张表只是"辅助信息"：查不到就不显示归属，绝不能让整个列表 500
            log.warn("查房间归属失败，本次列表不显示群号：{}", t.toString());
            return Map.of();
        }
        return map;
    }

    /** 一行场次的 JSON。字段名全部小写开头，前端直接对应。 */
    private Map<String, Object> item(LiveRecordFile row, Map<Long, List<Long>> groupsOfRoom) {
        boolean recording = "RECORDING".equalsIgnoreCase(row.getStatus());
        boolean failed = "FAILED".equalsIgnoreCase(row.getStatus());
        Path file = failed ? null : records.fileOf(row);
        boolean mp4 = file != null && file.getFileName().toString().toLowerCase().endsWith(".mp4");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fid", row.getFid());
        item.put("roomId", row.getRoomId());
        item.put("uname", row.getUname() == null || row.getUname().isBlank()
                ? "房间" + row.getRoomId() : row.getUname());
        item.put("title", row.getTitle());
        item.put("startTime", row.getStartTime());
        item.put("durationMs", row.getDurationMs());
        item.put("sizeBytes", row.getSizeBytes());
        item.put("status", row.getStatus());
        item.put("keep", Integer.valueOf(1).equals(row.getKeep()));
        item.put("compressed", Integer.valueOf(1).equals(row.getCompressed()));
        // 「能不能播」由服务端算，前端不要自己按 status 猜：文件可能已被手工删掉，
        // 那时 status 还是 DONE，猜出来的按钮点下去只会得到 500
        item.put("playable", !recording && !failed && file != null);
        item.put("missingFile", !recording && !failed && file == null);
        item.put("mp4", mp4);
        // 「实时」= 正在录 + 预览切片已就绪（ffmpeg 写出第一个 m3u8 之后才为真）。
        // 前端据此决定显示「▶ 实时」还是只显示「● 录制中」徽章 —— 两者不是一回事：
        // 刚起录的头几秒确实在录，但还没有可播的切片。
        item.put("live", recording && records.previewDirOf(row) != null);
        // 归属群（只有全群视图才非空）。空列表 = 没有任何群订阅它（例如取消订阅后留下的历史场次）
        List<Long> groups = groupsOfRoom.get(row.getRoomId());
        item.put("groups", groups == null ? List.of() : groups);
        return item;
    }

    // ================================================================== 播放 / 下载

    /**
     * 转码预备：{@code GET /record/api/{token}/prepare/{fid}}。
     *
     * <p>单独一个接口的理由是<b>能说实话</b>：直接在 {@code <video>} 的地址上做转码的话，
     * 失败只会变成播放器上一个不动的圈；这里却能把"这一场没录到文件"、
     * "flv 转 mp4 失败（ffmpeg exit=N）"原样回给页面显示。
     *
     * @return {@code {"ok":true,"converted":true,"sizeBytes":…}} 或 {@code {"ok":false,"error":"…"}}
     */
    @GetMapping("/api/{token}/prepare/{fid}")
    public ResponseEntity<?> prepare(@PathVariable String token, @PathVariable Long fid) {
        RecordWebAuth.View view = resolveView(token);
        if (view == null) {
            return notFound();
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            return jsonError(404, "找不到编号 " + fid + " 的录播（可能已被容量巡检清理）");
        }
        if (!inScope(scopeOf(view), row)) {
            return jsonError(403, "编号 " + fid + " 不在可见范围内");
        }
        String before = row.getPath();
        boolean wasMp4 = before != null && before.toLowerCase().endsWith(".mp4");
        try {
            Path file = records.playableFile(fid);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("converted", !wasMp4);
            body.put("sizeBytes", sizeOf(file));
            return ResponseEntity.ok(body);
        } catch (IOException e) {
            log.info("录播 {} 准备播放失败：{}", fid, e.toString());
            return jsonError(500, e.getMessage() == null ? "准备失败" : e.getMessage());
        }
    }

    /** 在线播放（{@code inline}，支持 Range —— 拖进度条靠它）。 */
    @GetMapping("/api/{token}/play/{fid}")
    public void play(@PathVariable String token, @PathVariable Long fid,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        serveFile(token, fid, request, response, false);
    }

    /** 下载（{@code attachment}，同样支持 Range —— 断点续传/多线程下载靠它）。 */
    @GetMapping("/api/{token}/download/{fid}")
    public void download(@PathVariable String token, @PathVariable Long fid,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        serveFile(token, fid, request, response, true);
    }

    private void serveFile(String token, Long fid, HttpServletRequest request,
                           HttpServletResponse response, boolean attachment) throws IOException {
        RecordWebAuth.View view = resolveView(token);
        if (view == null) {
            writeError(response, 404, "链接无效或已失效");
            return;
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            writeError(response, 404, "找不到编号 " + fid + " 的录播");
            return;
        }
        // ★ 令牌只回答"你是哪个群"，不能回答"这一场归不归你"：
        //   fid 是自增的，只校验令牌等于把全站录播挂在一个可枚举的 id 上
        if (!inScope(scopeOf(view), row)) {
            writeError(response, 403, "编号 " + fid + " 不在可见范围内");
            return;
        }
        Path file;
        try {
            file = records.playableFile(fid);
        } catch (IOException e) {
            log.info("录播 {} 取文件失败：{}", fid, e.toString());
            writeError(response, 500, e.getMessage() == null ? "取文件失败" : e.getMessage());
            return;
        }
        String name = records.suggestedFileName(row, file.getFileName().toString()
                .toLowerCase().endsWith(".mp4"));
        stream(response, request, file, name, attachment);
    }

    /**
     * 把文件按 HTTP 语义发出去（支持单段 Range）。
     * <p>Range 的解析与搬运都在 {@link HttpFileSupport} 里 —— 与给 NapCat 下载那条路
     * 共用同一份实现（那种"复制两份、只改一处"的代码不会报错，只会有一个出口能越界读）。
     */
    private static void stream(HttpServletResponse response, HttpServletRequest request,
                               Path file, String name, boolean attachment) throws IOException {
        long total = Files.size(file);
        HttpFileSupport.ByteRange range = HttpFileSupport.parseRange(request.getHeader("Range"), total);
        if (range == null) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + total);
            return;
        }
        response.setContentType(HttpFileSupport.contentType(name));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Disposition", HttpFileSupport.contentDisposition(name, attachment));
        response.setContentLengthLong(range.length());
        if (range.partial()) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range",
                    "bytes " + range.start() + "-" + range.end() + "/" + total);
        }
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = response.getOutputStream()) {
            HttpFileSupport.copyRange(in, out, range.start(), range.length());
        }
    }

    // ================================================================== 实时预览（HLS）

    /**
     * 实时预览索引：{@code GET /record/live/{token}/{fid}/index.m3u8}。
     *
     * <p>读的是录制时 ffmpeg 的第二路输出（{@code <场次>/preview/pNNN/index.m3u8}），
     * <b>不是</b> flv —— flv 没索引、录制中根本没法播（理由见 {@code LiveRecorder#recordCommand}）。
     *
     * <p>⚠️ 必须<b>改写切片路径</b>：ffmpeg 是用绝对路径写的 {@code -hls_segment_filename}，
     * 原样返回的话浏览器会去请求 {@code /record/seg00001.ts} 这种容器内的路径（必然 404）。
     * 这里统一改写成相对本接口的 {@code seg/<文件名>}，于是切片端点能拿到同一个 {@code token}/{fid}
     * 再做一次权限校验 —— <b>切片也走鉴权，不能变成"知道文件名就能拉"</b>。
     */
    @GetMapping(value = "/live/{token}/{fid}/index.m3u8")
    public ResponseEntity<?> liveIndex(@PathVariable String token, @PathVariable Long fid) {
        RecordWebAuth.View view = resolveView(token);
        if (view == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "链接无效或已失效"));
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "找不到编号 " + fid + " 的录播"));
        }
        if (!inScope(scopeOf(view), row)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "编号 " + fid + " 不在可见范围内"));
        }
        Path dir = records.previewDirOf(row);
        if (dir == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false,
                    "error", "这一场现在没有实时画面（可能刚起录、还没写出切片，或已经录完）"));
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(dir.resolve("index.m3u8"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // ffmpeg 每 2 秒重写一次 m3u8，极少数时刻会撞上"正在写"的窗口。
            // 回 503 让播放器自己重试，比回 404（= 这一场没有实时）准确得多
            log.debug("读实时索引失败（可能正被 ffmpeg 重写）：{}", e.toString());
            return ResponseEntity.status(503).body(Map.of("ok", false, "error", "索引正在生成，请稍候"));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .cacheControl(CacheControl.noStore())
                .body(rewritePlaylist(lines));
    }

    /**
     * 把 ffmpeg 写的 m3u8 改写成"切片都指向本服务的 {@code seg/} 端点"。
     *
     * <p>抽成独立方法是为了能<b>单独验</b>：这段是纯字符串逻辑，一旦把注释行也改写
     * （或者把 {@code #EXTINF} 后面的时长当切片名），播放器表现是"列表能拿到、就是不出画面"，
     * 在浏览器里极难定位。实测 ffmpeg 6.1 写的是纯文件名（不是绝对路径），
     * 但万一以后换版本改成绝对路径，这里取 basename 也能兜住。
     */
    static String rewritePlaylist(List<String> lines) {
        StringBuilder sb = new StringBuilder(lines.size() * 24);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                sb.append(t).append('\n');
                continue;
            }
            int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
            sb.append("seg/").append(slash >= 0 ? t.substring(slash + 1) : t).append('\n');
        }
        return sb.toString();
    }

    /**
     * 实时预览切片：{@code GET /record/live/{token}/{fid}/seg/seg00001.ts}。
     *
     * <p>⚠️ 切片名走<b>白名单正则</b>，不拼任意路径：这是唯一一个"文件名来自 URL"的入口，
     * 不校验就能用 {@code ../} 读到磁盘上任何东西（{@code token} 也挡不住这个）。
     *
     * <p>{@code delete_segments} 会把窗口外的切片删掉，所以"列表里有、点下去 404"是正常现象
     * —— 播放器遇到 404 会回到索引重新对齐，不需要额外处理。
     */
    @GetMapping("/live/{token}/{fid}/seg/{name}")
    public void liveSegment(@PathVariable String token, @PathVariable Long fid,
                            @PathVariable String name,
                            HttpServletResponse response) throws IOException {
        RecordWebAuth.View view = resolveView(token);
        if (view == null) {
            writeError(response, 404, "链接无效或已失效");
            return;
        }
        if (!SEGMENT_NAME.matcher(name).matches()) {
            writeError(response, 400, "切片名不合法");
            return;
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            writeError(response, 404, "找不到编号 " + fid + " 的录播");
            return;
        }
        if (!inScope(scopeOf(view), row)) {
            writeError(response, 403, "编号 " + fid + " 不在可见范围内");
            return;
        }
        Path dir = records.previewDirOf(row);
        if (dir == null) {
            writeError(response, 404, "这一场现在没有实时画面");
            return;
        }
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            // 已被 delete_segments 回收，或播放器在追一个刚滚出窗口的切片
            writeError(response, 404, "切片已滚出窗口");
            return;
        }
        long total = Files.size(file);
        response.setContentType("video/mp2t");
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(total);
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /**
     * HLS 播放库（hls.js）：{@code GET /record/hls.js}。
     *
     * <p>为什么不从 CDN 引：这个机器人在国内群聊里用，公网 CDN 时通时不通；
     * 而"点开实时却没画面"是最难解释的故障。打进 jar 只有 400KB，一次性成本换确定性。
     *
     * <p>⚠️ 它<b>不是</b>凭据：内容对所有人一样、不含任何录播数据，所以公开可缓存。
     * （Safari 原生支持 HLS，根本不加载它；Chrome/Edge 需要。）
     */
    @GetMapping(value = "/hls.js", produces = "application/javascript; charset=utf-8")
    public ResponseEntity<byte[]> hlsJs() {
        byte[] js = HLS_JS;
        if (js == null) {
            return ResponseEntity.status(404).body(new byte[0]);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript; charset=utf-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(js);
    }

    /** hls.js 的内容（首次请求时读一次）；缺失时保持 {@code null}，端点回 404。 */
    private static volatile byte[] HLS_JS;

    /** 切片文件名的白名单：只允许 {@code seg} + 数字 + {@code .ts} */
    private static final Pattern SEGMENT_NAME = Pattern.compile("^seg\\d{1,6}\\.ts$");

    private static final String HLS_JS_RESOURCE = "record/hls.min.js";

    @PostConstruct
    void loadHlsJs() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(HLS_JS_RESOURCE)) {
            if (in == null) {
                log.warn("录播网页的播放库 {} 不在 classpath 里 —— 实时预览在 Chrome 上会不可用"
                        + "（Safari 用原生 HLS，不受影响）", HLS_JS_RESOURCE);
                return;
            }
            HLS_JS = in.readAllBytes();
            log.info("录播实时预览播放库已就绪：{}（{} KB）", HLS_JS_RESOURCE, HLS_JS.length / 1024);
        } catch (Throwable t) {
            log.warn("加载 {} 失败，实时预览在 Chrome 上不可用：{}", HLS_JS_RESOURCE, t.toString());
        }
    }

    // ================================================================== 管理

    /**
     * 打 / 取消保留标记：{@code POST /record/api/{token}/keep/{fid}?keep=1}。
     *
     * <p>保留 = 既不参与自动压缩、也不被自动删除（用户眼里的"珍藏"）。
     */
    @PostMapping("/api/{token}/keep/{fid}")
    public ResponseEntity<?> keep(@PathVariable String token, @PathVariable Long fid,
                                  @RequestParam(defaultValue = "1") int keep,
                                  @RequestHeader(value = RecordWebAuth.MANAGE_HEADER, required = false)
                                  String manage) {
        ResponseEntity<?> denied = guardManage(token, manage);
        if (denied != null) {
            return denied;
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            return jsonError(404, "找不到编号 " + fid + " 的录播");
        }
        boolean on = keep != 0;
        if (!records.setKeep(fid, on)) {
            return jsonError(500, "改保留标记失败");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("fid", fid);
        body.put("keep", on);
        body.put("message", on ? "已标记保留：不参与自动压缩，也不会被自动删除"
                : "已取消保留：重新参与自动压缩与淘汰");
        return ResponseEntity.ok(body);
    }

    /** 删一场（连文件目录一起）：{@code POST /record/api/{token}/delete/{fid}}。 */
    @PostMapping("/api/{token}/delete/{fid}")
    public ResponseEntity<?> delete(@PathVariable String token, @PathVariable Long fid,
                                    @RequestHeader(value = RecordWebAuth.MANAGE_HEADER, required = false)
                                    String manage) {
        ResponseEntity<?> denied = guardManage(token, manage);
        if (denied != null) {
            return denied;
        }
        LiveRecordFile row = records.findFile(fid);
        if (row == null) {
            return jsonError(404, "找不到编号 " + fid + " 的录播");
        }
        long size = row.getSizeBytes() == null ? 0 : row.getSizeBytes();
        if (!records.deleteRecord(fid)) {
            return jsonError(409, "删除失败：这一场可能正在录制中，或文件正被占用");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("fid", fid);
        body.put("freedBytes", size);
        body.put("message", "已删除，文件已一并清掉");
        return ResponseEntity.ok(body);
    }

    /**
     * 管理动作的统一门。
     *
     * <p>两步，缺一不可：
     * <ol>
     *   <li>路径里的令牌得是<b>有效</b>的（否则连页面都不该能打开）；</li>
     *   <li>{@link RecordWebAuth#MANAGE_HEADER} 头里的值得是<b>管理码</b>。</li>
     * </ol>
     * <p>为什么第 2 步不能省：群令牌是设计上就要<b>转发给整个群</b>的东西，
     * 让它顺带带上删除权，等于"把链接发出去 = 把删除权发出去"。
     *
     * @return {@code null} 表示放行；否则返回要直接下发的拒绝响应
     */
    private ResponseEntity<?> guardManage(String token, String manage) {
        if (!auth.enabled()) {
            return notFound();
        }
        if (auth.resolve(token) == null) {
            return notFound();
        }
        if (!auth.isAdminToken(manage)) {
            return jsonError(403, "需要管理码才能做这个操作。管理码在 QQ 里发「录播管理码」私聊获取。");
        }
        return null;
    }

    // ================================================================== 工具

    /** 解出令牌代表的身份；网页总闸关着时一律 {@code null}。 */
    private RecordWebAuth.View resolveView(String token) {
        return auth.enabled() ? auth.resolve(token) : null;
    }

    /**
     * 这个身份能看到的房间集合。
     *
     * @return {@code null} = <b>不按房间过滤</b>（只有管理码走这条）；
     *         其余情况下是"本群曾经订阅过的房间号"，可能是空集（= 什么都看不到）
     */
    private Collection<Long> scopeOf(RecordWebAuth.View view) {
        return view.admin() ? null : records.roomsOfGroup(view.groupId());
    }

    /**
     * 一场录播在不在可见范围内。
     *
     * @param scope {@code null} = 不限
     */
    private static boolean inScope(Collection<Long> scope, LiveRecordFile row) {
        return scope == null || (row.getRoomId() != null && scope.contains(row.getRoomId()));
    }

    private static ResponseEntity<Map<String, Object>> jsonError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 已经开始的响应（流式接口）没法再返回 {@code ResponseEntity}，直接写 body。 */
    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("ok", false, "error", message)));
    }

    private static ResponseEntity<String> notFound() {
        return ResponseEntity.status(404)
                .contentType(TEXT_HTML_UTF8)
                .body("<!doctype html><meta charset=\"utf-8\">"
                        + "<h3>链接无效或已失效</h3>"
                        + "<p>请让群里的人重新发一次「录播网页」取得新链接"
                        + "（如果刚刚重置过令牌，旧链接会立刻失效）。</p>");
    }

    private static final MediaType TEXT_HTML_UTF8 =
            MediaType.parseMediaType("text/html;charset=UTF-8");

    private static long sizeOf(Path file) {
        try {
            return file != null && Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 读页面（只读一次；页面是打进 jar 的，运行期不会变）。 */
    private String page() {
        String cached = cachedPage;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedPage == null) {
                try (InputStream in = RecordWebController.class.getClassLoader()
                        .getResourceAsStream(PAGE_RESOURCE)) {
                    if (in == null) {
                        log.error("录播网页的页面文件 {} 不在 classpath 里（打包时漏了 resources/static？）",
                                PAGE_RESOURCE);
                        return null;
                    }
                    cachedPage = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.error("读取录播网页页面文件 {} 失败：{}", PAGE_RESOURCE, e.toString());
                    return null;
                }
            }
            return cachedPage;
        }
    }
}
