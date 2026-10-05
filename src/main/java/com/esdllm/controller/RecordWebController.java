package com.esdllm.controller;

import com.esdllm.common.HttpFileSupport;
import com.esdllm.model.LiveRecordFile;
import com.esdllm.service.LiveRecordService;
import com.esdllm.service.RecordWebAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        for (LiveRecordFile row : rows) {
            items.add(item(row));
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

    /** 一行场次的 JSON。字段名全部小写开头，前端直接对应。 */
    private Map<String, Object> item(LiveRecordFile row) {
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
     *
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
        response.setHeader("Content-Disposition", (attachment ? "attachment" : "inline")
                + "; filename=\"" + HttpFileSupport.safeAsciiName(name) + "\"");
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
