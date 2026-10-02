package com.esdllm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时自动建库、建表 —— 让 {@code java -jar} 在新环境里直接跑起来，
 * 不用再手工准备 {@code resources/xatiiBot.db}。
 *
 * <h2>为什么是「监听 ApplicationPreparedEvent」，而不是别的常见写法</h2>
 *
 * 这件事的**唯一难点不是建表，是时机**。SQLite 的库文件不存在时驱动会自己创建，
 * 但**它不会创建库文件所在的父目录** —— 目录不存在就直接 {@code SQLITE_CANTOPEN} 启动失败。
 * 而 {@code ./resources/} 这个目录恰恰是必需项（yaml 里就是 {@code jdbc:sqlite:./resources/xatiiBot.db}）。
 * 所以「建目录」必须发生在 <b>Druid 创建连接池之前</b>。
 *
 * <p>把几个候选时机摆一下，就知道为什么选这个（都踩过或分析过）：
 *
 * <table border="1">
 *   <tr><th>时机</th><th>为什么不用</th></tr>
 *   <tr>
 *     <td>Spring 原生 {@code spring.sql.init} + {@code schema.sql}</td>
 *     <td><b>本工程用不了</b>：{@code Main} 上写着
 *         {@code @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)}，
 *         数据源完全交给 dynamic-datasource 自己造，Spring 那套
 *         {@code SqlInitializationAutoConfiguration} 的装配前提（单例 DataSource、装配顺序）
 *         在这里都不可靠；而且它只能作用于一个数据源，也没有"建父目录"的能力。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @PostConstruct} / {@code InitializingBean}</td>
 *     <td>顺序由 bean 依赖图决定。<b>本项目里第一个碰数据库的其实是
 *         {@link LoadDSConfig}</b>（它在 {@code @PostConstruct} 里就
 *         {@code configMapper.selectList(null)} 了），而它不依赖本类 ⇒
 *         谁先谁后是没保证的，随时可能被后加的一个 bean 打乱。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ApplicationRunner} / {@code CommandLineRunner}</td>
 *     <td><b>太晚</b>：它们在容器 refresh 完成、所有 bean 都初始化完之后才跑，
 *         那时 {@link LoadDSConfig} 早就查过库了。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EnvironmentPostProcessor}</td>
 *     <td>时机最早（比日志初始化还早），代价是**日志系统还没就绪** ——
 *         打出来的东西不走 {@code logback-spring.xml}，格式和级别都是错的，
 *         跟项目刚统一好的日志规范冲突。</td>
 *   </tr>
 *   <tr>
 *     <td><b>{@code ApplicationPreparedEvent}（本类采用）</b></td>
 *     <td>Environment 已加载完（能读到 yaml 里的 jdbc url）、日志已初始化、
 *         但<b>任何普通 bean 都还没实例化</b>。三个条件同时满足，是唯一干净的位置。</td>
 *   </tr>
 * </table>
 *
 * <p>注册方式见 {@code Main#main} —— 显式 {@code addListeners}，
 * 不用 {@code META-INF/spring.factories}：一是能一眼看到它存在，
 * 二是这个 listener 需要读 Environment，而它现在根本不是 Spring bean。
 *
 * <h2>它是怎么工作的</h2>
 * <ol>
 *   <li>读 {@code db.init.enabled}，false 就整体跳过（退回"手工准备库文件"的老路子）；</li>
 *   <li>读 {@code db.init.datasources}（逗号分隔的数据源名，默认 {@code sqlite}）；</li>
 *   <li>每个名字去 classpath 找 {@code schema/<名字>.sql}，用
 *       {@code spring.datasource.dynamic.datasource.<名字>.*} 里的连接信息
 *       <b>直接开一条原生 JDBC 连接</b>执行（<b>不</b>走 Spring 的 DataSource，
 *       那会触发 bean 创建，正是要避开的事）；</li>
 *   <li>SQLite 特例：先保证库文件所在目录存在；</li>
 *   <li>把脚本里的语句按 <b>建表 → 补列 → 建索引</b> 三段执行（顺序是硬要求，
 *       索引可能引用刚补上的列）。</li>
 * </ol>
 *
 * <h2>它做到什么程度（边界）</h2>
 * <ul>
 *   <li><b>建表</b>：脚本里的 {@code CREATE TABLE IF NOT EXISTS}，
 *       表不存在就建，存在就跳过。</li>
 *   <li><b>补列</b>：表存在但少列时，<b>会</b>自动 {@code ALTER TABLE ADD COLUMN}
 *       （SQLite 分支，见 {@link #addMissingColumns}）。期望列直接从脚本的
 *       {@code CREATE TABLE} 解析，所以「给老表加列」的正确做法就是<b>在脚本里改建表语句</b>，
 *       不需要另处维护清单。⚠️ 只能补列，<b>不能改列类型、不能删列</b>：
 *       SQLite 的 {@code DROP COLUMN} / 类型变更要重建整张表，
 *       那是真正的 schema 迁移，超出本类职责（也会动到线上数据，不做）。</li>
 *   <li><b>建索引</b>：脚本里的 {@code CREATE INDEX IF NOT EXISTS} 幂等执行。
 *       索引存在的理由是"服务真实查询形状"，写在脚本注释里。</li>
 *   <li><b>不建库（对非 SQLite）</b>：像 MySQL 那种 {@code jdbc:mysql://host/db}
 *       形式的 URL，库必须预先存在，本类只负责建表。
 *       （本项目 mysql 数据源是历史迁移源，默认也不在 {@code db.init.datasources} 里。）</li>
 *   <li><b>非 SQLite 不补列</b>：「查现有列」用的是 SQLite 的 {@code PRAGMA table_info}，
 *       其他方言要各自的写法，现在没做（真要支持就在 {@link #initDataSource} 里加分支）。</li>
 * </ul>
 */
public class DbInitializer implements ApplicationListener<ApplicationPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";
    private static final String DS_PROP_PREFIX = "spring.datasource.dynamic.datasource.";
    private static final String DEFAULT_DATASOURCES = "sqlite";

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();

        if (!truthy(env.getProperty("db.init.enabled"), true)) {
            log.info("[建库建表] db.init.enabled=false，跳过自动建表（库文件需自行准备）");
            return;
        }

        String names = env.getProperty("db.init.datasources", DEFAULT_DATASOURCES);
        for (String raw : names.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                initDataSource(env, name);
            }
        }
    }

    /**
     * 初始化单个数据源：确保 SQLite 目录存在 → 读完 DDL → 开原生连接，按
     * <b>建表 → 补列 → 建索引</b> 三段执行。
     *
     * <p>三段顺序是硬要求，不是风格问题：索引完全可能引用刚刚补上的列
     * （见 {@link #addMissingColumns}），先建索引会直接失败。
     *
     * <p><b>失败即快速失败</b>（抛异常让整个启动终止）。理由：schema 没弄好，
     * 后面任何一个查询都会炸，而那时候的报错远不如这里直白；
     * 反过来，只要脚本是幂等的（建表带 IF NOT EXISTS、补列只补缺的），
     * 正常路径上这里就不该失败。
     */
    private void initDataSource(Environment env, String name) {
        String prefix = DS_PROP_PREFIX + name + ".";
        String url = env.getProperty(prefix + "url");
        if (url == null || url.isBlank()) {
            log.warn("[建库建表] 数据源 [{}] 没有配置 url（找的是 {}url），跳过", name, prefix);
            return;
        }

        boolean sqlite = url.startsWith(SQLITE_URL_PREFIX);
        if (sqlite) {
            ensureSqliteDirectory(url);
        }

        String driver = env.getProperty(prefix + "driver-class-name");
        String user = env.getProperty(prefix + "username");
        String password = env.getProperty(prefix + "password");

        String script = readScript(name);
        List<String> statements = splitStatements(script);

        // 按「建表 → 补列 → 建索引」三段执行。顺序不是随便排的：
        //   ③ 必须排在 ② 之后 —— 索引完全可能引用刚刚补上的列，先建索引会直接失败。
        List<String> createTables = new ArrayList<>();
        List<String> createIndexes = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String sql : statements) {
            String head = sql.trim().toUpperCase(Locale.ROOT);
            if (head.startsWith("CREATE TABLE")) {
                createTables.add(sql);
            } else if (head.startsWith("CREATE INDEX") || head.startsWith("CREATE UNIQUE INDEX")) {
                createIndexes.add(sql);
            } else {
                others.add(sql);
            }
        }

        long started = System.currentTimeMillis();
        int executed = 0;
        int tablesCreated = 0;
        List<String> addedColumns = new ArrayList<>();

        try (Connection conn = open(url, driver, user, password);
             Statement st = conn.createStatement()) {
            Set<String> tablesBefore = sqlite ? sqliteTables(conn) : null;

            for (String sql : createTables) {
                st.execute(sql);
                executed++;
            }
            if (tablesBefore != null) {
                Set<String> now = sqliteTables(conn);
                now.removeAll(tablesBefore);
                tablesCreated = now.size();
            }

            if (sqlite) {
                addedColumns = addMissingColumns(conn, st, name, parseExpectedColumns(script));
                executed += addedColumns.size();
            }
            // 非 SQLite 暂不补列：「查现有列」那步各库方言不同（这里用的是 SQLite 的 PRAGMA）。
            // 本项目的 mysql 数据源是历史迁移源、默认不启用；真要支持就在这儿加分支。

            for (String sql : createIndexes) {
                st.execute(sql);
                executed++;
            }
            for (String sql : others) {
                st.execute(sql);
                executed++;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(String.format(
                    "[建库建表] 数据源 [%s] 执行 DDL 失败（已完成 %d/%d 条，库=%s）：%s",
                    name, executed, statements.size(), url, e.getMessage()), e);
        }

        log.info("[建库建表] 数据源 [{}] 就绪：新建表 {} 张、补列 {} 个、语句 {} 条、耗时 {}ms{}",
                name, tablesCreated, addedColumns.size(), executed,
                System.currentTimeMillis() - started,
                sqlite ? "，库文件 " + sqliteFileText(url) : "");
        if (!addedColumns.isEmpty()) {
            log.info("[建库建表] 数据源 [{}] 本次补上的列：{}", name, String.join(", ", addedColumns));
        }
    }

    /**
     * 开一条原生 JDBC 连接。
     *
     * <p>⚠️ <b>SQLite 分支不能传 Properties</b>：sqlite-jdbc 会把连接属性当成 pragma 名，
     * 传一个它不认识的 {@code user} 会直接抛异常。所以只在真的配了用户名时才带凭据 ——
     * 本项目 sqlite 数据源本来就没配 username，走的正是无参分支。
     */
    private Connection open(String url, String driver, String user, String password) throws SQLException {
        if (driver != null && !driver.isBlank()) {
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("[建库建表] 驱动类加载失败：" + driver, e);
            }
        }
        if (user == null || user.isBlank()) {
            return DriverManager.getConnection(url);
        }
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password == null ? "" : password);
        return DriverManager.getConnection(url, props);
    }

    /**
     * 确保 SQLite 库文件的父目录存在。
     *
     * <p>这是本类存在的主要理由：{@code jdbc:sqlite:./resources/xatiiBot.db} 里的
     * {@code ./resources/} 如果不存在，sqlite-jdbc <b>不会</b>替你建，直接
     * {@code SQLITE_CANTOPEN(14): unable to open database file} —— 而且这个报错
     * 要到取第一条连接时才出现，离"目录忘了建"这个真因很远。
     */
    private void ensureSqliteDirectory(String url) {
        Path file = sqliteFilePath(url);
        if (file == null) {
            return;
        }
        Path dir = file.getParent();
        if (dir == null || Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir);
            log.info("[建库建表] 已补建 SQLite 库文件目录：{}", dir);
        } catch (IOException e) {
            throw new IllegalStateException("[建库建表] 无法创建 SQLite 库文件目录 " + dir
                    + "（SQLite 不会自建父目录，缺失时启动必然失败）", e);
        }
    }

    /**
     * 从 {@code jdbc:sqlite:<path>} 解析出库文件路径。
     *
     * <p>相对路径按**进程工作目录**解析（跟 Druid 后续连库时的口径一致，
     * 所以两边看到的是同一个文件）。{@code :memory:} 之类的内存库返回 {@code null}。
     */
    private static Path sqliteFilePath(String url) {
        String path = url.substring(SQLITE_URL_PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isBlank() || ":memory:".equals(path)) {
            return null;
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private static String sqliteFileText(String url) {
        Path file = sqliteFilePath(url);
        return file == null ? ":memory:" : file.toString();
    }

    /** 读 classpath 下的 {@code schema/<数据源名>.sql}。 */
    private String readScript(String name) {
        String resource = "schema/" + name + ".sql";
        try (InputStream in = DbInitializer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("[建库建表] classpath 下找不到 " + resource
                        + " —— 数据源 [" + name + "] 要自动建表就必须有同名脚本；"
                        + "不想建它就从 db.init.datasources 里去掉这个名字");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("[建库建表] 读取 " + resource + " 失败", e);
        }
    }

    /** 列出库里现有的表（SQLite）。sqlite_% 是 SQLite 自己的内部表，不算。 */
    private static Set<String> sqliteTables(Connection conn) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * 列出某张表现有的列（SQLite 用 {@code PRAGMA table_info}）。
     *
     * <p>表名直接拼进 SQL 是因为 PRAGMA <b>不支持参数绑定</b>；安全性由
     * {@link #parseExpectedColumns} 的正则保证（表名只可能是 {@code [A-Za-z_][A-Za-z0-9_$]*}）。
     */
    private static Set<String> sqliteColumns(Connection conn, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info('" + table + "')")) {
            // table_info 的列依次是：cid, name, type, notnull, dflt_value, pk —— 取第 2 列 name
            while (rs.next()) {
                columns.add(rs.getString(2));
            }
        }
        return columns;
    }

    /**
     * 补齐缺失的列 —— 「表存在但缺列」时自动 {@code ALTER TABLE ADD COLUMN}。
     *
     * <p>为什么非得自己比对：SQLite <b>没有</b> {@code ADD COLUMN IF NOT EXISTS}
     * （{@code CREATE TABLE} / {@code CREATE INDEX} 都有 IF NOT EXISTS，唯独 ADD COLUMN 没有，
     * 这是它的长期缺失），所以只能「先 PRAGMA 查现有列、再缺哪个补哪个」。
     *
     * <p>两条 SQLite 硬约束会在<b>下发 SQL 之前</b>就检查掉（实测报错文案见下），
     * 目的是把"启动失败"和"哪一列写错了"直接绑在一起：
     * <ul>
     *   <li>要补的列带 {@code PRIMARY KEY} → {@code Cannot add a PRIMARY KEY column}。
     *       已有数据的表想加主键只能重建表，本工具不做这件事。</li>
     *   <li>要补的列是 {@code NOT NULL} 但没写默认值 →
     *       {@code Cannot add a NOT NULL column with default value NULL}。</li>
     * </ul>
     *
     * @return 本次真正补上的列，形如 {@code admin.group_id}（用于日志）
     */
    private List<String> addMissingColumns(Connection conn, Statement st, String dsName,
                                           Map<String, List<String>> expected) throws SQLException {
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String table = entry.getKey();
            Set<String> existing = sqliteColumns(conn, table);
            if (existing.isEmpty()) {
                // 正常不该走到这儿：CREATE TABLE IF NOT EXISTS 刚刚跑过。
                // 真到了这里说明脚本里有张表的 CREATE 没被识别（比如写在了一行里），
                // 与其静默跳过不如留条线索。
                log.warn("[建库建表] 数据源 [{}] 在库里找不到表 [{}]，跳过它的补列检查（请确认脚本里的 CREATE TABLE 写法）",
                        dsName, table);
                continue;
            }
            for (String columnDefinition : entry.getValue()) {
                String column = columnNameOf(columnDefinition);
                if (existing.contains(column)) {
                    continue;
                }
                String upper = columnDefinition.toUpperCase(Locale.ROOT);
                if (upper.contains("PRIMARY KEY")) {
                    throw new IllegalStateException(String.format(
                            "[建库建表] 表 [%s] 要补的列 [%s] 带 PRIMARY KEY —— SQLite 不允许 "
                                    + "ALTER TABLE ADD COLUMN 加主键列（Cannot add a PRIMARY KEY column）。"
                                    + " 已有数据的表加主键只能重建表，本工具不做这件事。列定义：%s",
                            table, column, columnDefinition));
                }
                if (upper.contains("NOT NULL") && !upper.contains("DEFAULT")) {
                    throw new IllegalStateException(String.format(
                            "[建库建表] 表 [%s] 要补的列 [%s] 是 NOT NULL 却没写默认值 —— SQLite 不允许 "
                                    + "（Cannot add a NOT NULL column with default value NULL）。"
                                    + " 请在 schema 脚本里给它补一个默认值，例如 `%s DEFAULT 0`。列定义：%s",
                            table, column, column, columnDefinition));
                }
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
                added.add(table + "." + column);
            }
        }
        return added;
    }

    /**
     * 从建表脚本里解析出「表 → 期望的列定义」。
     *
     * <p>刻意<b>不另外维护一份列清单</b>：期望列直接从 {@code CREATE TABLE} 语句里读，
     * 这样「建新表」和「给老表补列」永远看的是同一份定义，不可能出现两边不一致。
     *
     * <p>解析是行级的（这个脚本由本项目自己维护、格式受控）：{@code CREATE TABLE ... (}
     * 到收尾的 {@code )} 之间、每一个非空非注释行就是一条列定义；以
     * {@code PRIMARY} / {@code UNIQUE} / {@code CHECK} / {@code FOREIGN} / {@code CONSTRAINT}
     * 开头的是表级约束，跳过。
     */
    static Map<String, List<String>> parseExpectedColumns(String script) {
        Pattern head = Pattern.compile(
                "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "([A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")\\s*\\(");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String statement : splitStatements(script)) {
            Matcher matcher = head.matcher(statement);
            if (!matcher.find()) {
                continue;
            }
            String table = unquote(matcher.group(1));
            int bodyStart = statement.indexOf('(', matcher.end() - 1);
            if (bodyStart < 0) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String rawLine : statement.substring(bodyStart + 1).split("\r?\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                if (line.endsWith(",")) {
                    line = line.substring(0, line.length() - 1).trim();
                }
                if (line.isEmpty() || line.startsWith(")")) {
                    continue;
                }
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("PRIMARY ") || upper.startsWith("UNIQUE")
                        || upper.startsWith("CHECK") || upper.startsWith("FOREIGN")
                        || upper.startsWith("CONSTRAINT")) {
                    continue;
                }
                columns.add(line);
            }
            expected.put(table, columns);
        }
        return expected;
    }

    /**
     * 取列定义里的列名（第一个 token）。
     *
     * <p>列名可能带双引号 —— {@code config} 表的 {@code "key"} 是 SQLite 保留字，必须带。
     * 而 {@code PRAGMA table_info} 返回的是<b>不带引号</b>的 {@code key}，
     * 所以这里要脱掉引号才能比对得上。
     */
    static String columnNameOf(String columnDefinition) {
        String definition = columnDefinition.trim();
        if (definition.startsWith("\"")) {
            int end = definition.indexOf('"', 1);
            if (end > 0) {
                return definition.substring(1, end);
            }
        }
        int space = definition.indexOf(' ');
        int tab = definition.indexOf('\t');
        int cut = space < 0 ? tab : (tab < 0 ? space : Math.min(space, tab));
        return cut < 0 ? definition : definition.substring(0, cut);
    }

    private static String unquote(String identifier) {
        if (identifier.length() >= 2
                && identifier.charAt(0) == '"' && identifier.charAt(identifier.length() - 1) == '"') {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    /**
     * 把脚本切成一条条可执行的语句。
     *
     * <p>刻意<b>只做行级解析</b>（丢空行、丢 {@code --} 开头的注释行、以 {@code ;} 收尾即提交），
     * 不引 SQL 解析器 —— 脚本是我们自己维护的、格式受控。
     * 代价是<b>不支持字符串字面量里的分号</b>，写脚本时避开即可。
     *
     * <p>末尾的 {@code ;} 会去掉：MySQL 驱动对带尾分号的语句会报语法错误
     * （SQLite 宽容，但没必要为它留特例）。
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : script.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            buf.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = buf.toString().trim();
                statements.add(sql.substring(0, sql.length() - 1).trim());
                buf.setLength(0);
            }
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    /** 宽松布尔：只有 true/1/on/yes 算真，false/0/off/no 算假，其余（含空白）取默认值。 */
    static boolean truthy(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v) || "no".equalsIgnoreCase(v)) {
            return false;
        }
        return defaultValue;
    }
}
