-- ============================================================================
-- XatiiBot SQLite 建表 / 补列 / 建索引脚本
--
-- 由 com.esdllm.config.DbInitializer 在启动时（bean 实例化之前）自动执行。
-- 这个脚本**每次启动都会跑一遍**，而线上那个库里躺着真实数据（签到记录、订阅、B 站 Cookie），
-- 所以：只允许 CREATE ... IF NOT EXISTS / ALTER TABLE ADD COLUMN，DROP、DELETE、UPDATE 一概禁止。
--
-- 执行顺序（DbInitializer 保证，不要依赖脚本里的书写顺序）：
--   ① 所有 CREATE TABLE   → 补齐缺失的表
--   ② 补列                 → 表已存在但缺列时 ALTER TABLE ADD COLUMN
--   ③ 所有 CREATE INDEX + 其余语句 → 必须排在补列之后，因为索引完全可能引用新增的列
--
-- 三条纪律：
--   ① 除非必要不要 DROP / DELETE / UPDATE；这个脚本每次启动都跑，在这里做数据变更等于每次启动都做一次。
--   ② 表名 / 列名 / 类型**照抄生产库**（用 `PRAGMA table_info` 从现网导出后核对），
--      一个字母都不能改 —— MyBatis-Plus 靠驼峰映射列名，改一个字运行时就是
--      "no such column"。⚠️ 典型陷阱：config.creat_time 那个列名**确实少了个 e**，
--      但实体 com.esdllm.model.Config#creatTime 也是这么拼的，两边一致
--      ⇒ 保持原样，别"顺手修正"。
--
-- 🔑 **补列的约定**（想给已有的表加列，就在这里改 CREATE TABLE，DbInitializer 会自动补）：
--   · 期望列是**直接从下面的 CREATE TABLE 里解析出来的**，不需要另外维护一份列清单；
--   · SQLite 硬约束（实测）：
--       - 补的列**不能带 PRIMARY KEY**（报 "Cannot add a PRIMARY KEY column"）；
--       - NOT NULL 列**必须带默认值**（报 "Cannot add a NOT NULL column with default value NULL"），
--         默认值用常量，`DEFAULT 0` / `DEFAULT (0)` / `DEFAULT ''` 都可以；
--       - 现有的 NOT NULL 列都没有默认值（create_time 之类），它们属于建表时的列，不受影响。
--   · 违反上面两条时**启动会直接失败**并指出是哪张表哪个列 —— 这是故意的：
--     比上线后运行时 "no such column" 好查太多。
--
-- 写法约定（DbInitializer#splitStatements 按此解析）：
--   · 一条语句以分号结尾；分号后可以有空白，但不要再跟内容；
--   · 以 -- 开头的整行是注释，会被跳过；空行忽略；
--   · CREATE TABLE 的每一行放一条列定义（补列解析按行读）。
-- ============================================================================


-- 管理员表：group_id 为 NULL 表示"全局管理员"（见 BotAdminChecker.isBotAdmin / isBotOwner）
CREATE TABLE IF NOT EXISTS admin (
  admin_id    INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  qq_uid      INTEGER NOT NULL,
  group_id    INTEGER NULL,
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- 配置表："key" 是 SQLite 保留字，必须带双引号，去掉会语法错误
-- value 声明为 TEXT(256) 但实际存过 477 字符的 B 站 Cookie —— SQLite 不强制长度，
-- 所以没问题，照抄原样。
CREATE TABLE IF NOT EXISTS config (
  id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  "key"       TEXT(128),
  value       TEXT(256),
  creat_time  INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- 订阅表（直播 + 动态推送共用一行记录）
-- ⚠️ at_list 的类型 ANY(1024) 是历史遗留（当年用图形化工具建表留下的），
--    SQLite 解析它得到 NUMERIC 亲和性；但实际存进去的是 '[1, 2, 3]' 这种文本
--    （见 LongListTypeHandler#setNonNullParameter，直接把 List 丢给驱动），
--    永远带方括号 ⇒ 永远不是合法数字 ⇒ 永远按 TEXT 存储，所以亲和性无副作用。
--    照抄原样，避免新旧库出现"声明不同、行为万一不同"的分歧。
CREATE TABLE IF NOT EXISTS push_info (
  pid           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  room_id       INTEGER NOT NULL,
  group_id      INTEGER,
  qq_uid        INTEGER,
  at_all        INTEGER DEFAULT (0) NOT NULL,
  at_list       ANY(1024),
  live_status   INTEGER DEFAULT (0) NOT NULL,
  live_time     INTEGER,
  live_push     INTEGER DEFAULT (1),
  dynamic_push  INTEGER DEFAULT (1) NOT NULL,
  create_time   INTEGER NOT NULL,
  update_time   INTEGER NOT NULL,
  is_delete     INTEGER DEFAULT (0)
);

-- 签到表
CREATE TABLE IF NOT EXISTS sign_in_records (
  sid         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  qq_uid      INTEGER NOT NULL,
  group_id    INTEGER,
  empirical   REAL DEFAULT (0) NOT NULL,
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- ============================================================================
-- 录播（直播录制）
--
-- 两张表分工（刻意拆开；设计取舍都写在下面各表的注释里，别再找 docs/直播录制设计.md
-- —— 那份文档从来没写过，这里曾经指向它，属于死引用）：
--   live_record_sub  —— 「要自动录制哪些主播」的**配置**。加一行 = 盯上这个房间，
--                       开播就自动录；一行代表一个主播，长期存在，不随场次增删。
--   live_record_file —— 每一场录制的**产物**。一个主播录 N 次就有 N 行，
--                       文件路径/大小/时长/保留标记都在这里。
-- 拆开的原因：若合成一张表，就得在"配置行"和"产物行"之间反复插占位记录，
-- 查询和补列都会立刻变脏（一行到底代表主播还是代表一场？）。
-- ============================================================================

-- 录播订阅表：一行 = 一个要自动录制的主播（长期存在，与"录了几场"无关）
--
-- ⚠️ auto_record = 1 才参与开播检测；置 0 = 保留记录但暂停录制（比删掉再建更常用）。
-- ⚠️ quality 是期望清晰度 qn（10000 = 原画，实测）。留 NULL / ≤0 一律按原画处理。
--
-- 🔑 group_id = **这条订阅是谁要的**（QQ 群号）。NULL = 全局订阅（机器人在私聊里加的）。
--    它决定两件事，都与"录制"无关、只与**可见性**有关：
--      ① 「录播网页」按群隔离 —— 本群只能看到自己订阅过的房间录出来的东西；
--      ② 「录播订阅列表」群里只列本群的。
--    ⚠️ 它**不**决定"录几路"：同一房间被多个群订阅时是**多行**（每群一行），
--    而录制按 room_id 去重，仍然只录一路（见 LiveRecordServiceImpl#tick）。
--    所以这一列**不能**加 UNIQUE，也不该在 live_record_file 上冗余一份
--    （冗余的单值表达不了"两个群都能看同一场"）。
CREATE TABLE IF NOT EXISTS live_record_sub (
  sid         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  room_id     INTEGER NOT NULL,
  group_id    INTEGER,
  uid         INTEGER,
  uname       TEXT(128),
  auto_record INTEGER DEFAULT (1) NOT NULL,
  quality     INTEGER DEFAULT (10000),
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- 录播网页的访问令牌表：一行 = 一个令牌。
--
-- 存在的理由：浏览器里没有 QQ 身份，判定不了"你是不是本群的人"。所以走**群级共享令牌** ——
-- 由群里的 botadmin 发一次「录播网页」命令拿到链接，转给群成员；链接本身即凭证。
--
-- ⚠️ group_id 的两种含义（靠它区分两种令牌，不要再加 kind 列）：
--     · group_id 有值 —— **群令牌**。只能看这个群订阅过的房间；群成员用它浏览/播放/下载。
--     · group_id 为空 —— **全局管理码**。可看全部（含全局订阅），并可在网页上改保留 / 删录播。
--       它**不放链接里**，只由「录播管理码」命令私聊投递给 botadmin（群里回等于贴墙上）。
--       ⚠️ 单行约定：group_id IS NULL 只允许一行，代码里取/建时用 NULL 匹配同一行。
--       （这里不能给它加 UNIQUE —— SQLite 的唯一索引不约束 NULL，加了个寂寞。）
--
-- ⚠️ token 是 16 字节安全随机数的十六进制（32 字符），**不是**可推导的哈希：
--    重置 = 直接 UPDATE 成新值，旧链接立刻失效（没有"历史行"要清理）。
CREATE TABLE IF NOT EXISTS record_web_token (
  tid         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  group_id    INTEGER,
  token       TEXT(64) NOT NULL,
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- 录播文件表：一行 = 一场录制（从开播到结束）
--
-- ⚠️ status 取值（字符串，不是枚举表）：
--     RECORDING   正在录（进程内有 worker 在跟；启动时若还看到它 ⇒ 说明上次是异常退出）
--     DONE        录完了，文件是原始 .flv
--     COMPRESSED  已被容量巡检压缩过（降分辨率 + H.265），文件是 .mp4，原 .flv 已删
--     INTERRUPTED 进程重启/异常中断，文件可能不完整（保留着，等人处理或参与淘汰）
--     FAILED      一次都没录成（0 字节），无文件
-- ⚠️ keep = 1 表示"不参与自动淘汰"（用户手工标记的珍藏）。⚠️ 压缩也跳过 keep，
--    理由：压缩会降分辨率、丢画质，对"珍藏"是不可接受的损失。
-- ⚠️ sid 可空：订阅被取消后，已录好的文件记录还要留着（不然就变成"有文件没记录"，
--    既统计不到容量、也删不掉）。
-- ⚠️ size_bytes 是**整个场次目录**的占用（分片合并前的残留也算在内），
--    容量巡检以它为准做加减；文件被手工删掉时巡检会发现并修正（见 LiveRecordServiceImpl）。
--
-- 🔑 **刻意没有 group_id**（虽然查网页时要按群过滤，加一列看起来更省事）：
--    「这个文件属于哪个群」的真实来源是 **room_id ∈ 该群订阅过的房间**，
--    而一场直播可能被两个群同时订阅 —— 那样它属于两个群，一列放不下。
--    所以可见性一律由 live_record_sub 推导（见 LiveRecordSubMapper#selectEverySubscribedRoomIds
--    与 LiveRecordServiceImpl#roomsOfGroup），这张表只管"录出了什么"。
CREATE TABLE IF NOT EXISTS live_record_file (
  fid         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  sid         INTEGER,
  room_id     INTEGER NOT NULL,
  uid         INTEGER,
  uname       TEXT(128),
  title       TEXT(512),
  path        TEXT(512),
  size_bytes  INTEGER DEFAULT (0) NOT NULL,
  duration_ms INTEGER DEFAULT (0) NOT NULL,
  quality     INTEGER DEFAULT (0),
  start_time  INTEGER NOT NULL,
  end_time    INTEGER DEFAULT (0) NOT NULL,
  status      TEXT(24) DEFAULT 'RECORDING' NOT NULL,
  keep        INTEGER DEFAULT (0) NOT NULL,
  compressed  INTEGER DEFAULT (0) NOT NULL,
  create_time INTEGER NOT NULL,
  update_time INTEGER NOT NULL,
  is_delete   INTEGER DEFAULT (0) NOT NULL
);

-- 注：sqlite_sequence 由 SQLite 自动维护（只要用了 AUTOINCREMENT），不需要也不应该手工创建。


-- ============================================================================
-- 索引（2026-10-03 补）
--
-- 此前线上一个索引都没有，所有点查都是 SCAN 全表扫描。
-- 下面每条索引都是按**代码里的真实查询形状**定的，不是"感觉该加"；
-- 用 EXPLAIN QUERY PLAN 逐条比对过（加之前全是 SCAN，加之后全部 SEARCH ... USING INDEX）：
--
--   idx_config_key
--       服务 LoadDSConfig#updateConfig / #deleteConfig / #addConfig —— 条件恒为 key = ?
--       ⚠️ 这是热路径：每次动态推送都会把去重记录（pushedDynamicIds）写回 config 一次。
--
--   idx_admin_qq_uid_group
--       服务 BotAdminChecker 的三处 count，条件恒以 qq_uid 打头：
--         isBotAdmin   → qq_uid = ?
--         isBotOwner   → qq_uid = ? AND group_id IS NULL
--         isGroupAdmin → qq_uid = ? AND group_id = ?
--
--   idx_push_info_room_qq_uid_group
--       服务 PushInfoServiceImpl#getWrapper(roomId, qqUid, groupId)（添加/取消订阅）。
--       ⚠️ 说清楚它帮不上什么忙：push_info 的三条主路径其实是 selectList(null) 全表扫描
--       （livePush / dynamicPush / 启动时载入去重记录），那几处**索引救不了** ——
--       它们本来就要读全部订阅行。索引只让"增删订阅"这类点查受益。
--
--   idx_sign_in_records_qq_uid_group
--       服务 SignInRecordsServiceImpl#isSign —— 每次「签到」命令的热路径，
--       条件是 qq_uid = ? AND (group_id = ? 或 group_id IS NULL)。
--
-- 都是**普通（非唯一）索引**：表里本来就允许多条同 qq_uid / 同 room_id 的记录，
-- 用 UNIQUE 会直接把写入打挂。列顺序按「等值条件在前、选择性从高到低」排。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_config_key
    ON config ("key");

CREATE INDEX IF NOT EXISTS idx_admin_qq_uid_group
    ON admin (qq_uid, group_id);

CREATE INDEX IF NOT EXISTS idx_push_info_room_qq_uid_group
    ON push_info (room_id, qq_uid, group_id);

CREATE INDEX IF NOT EXISTS idx_sign_in_records_qq_uid_group
    ON sign_in_records (qq_uid, group_id);

--   idx_live_record_sub_room_id
--       服务 LiveRecordServiceImpl 的「这个房间要不要录 / 订阅在不在」（恒为 room_id = ?）。
--       ⚠️ 开播检测 tick 走的是 selectList(全表)，索引救不了那一条 —— 它本来就要读全部订阅行；
--       索引只让"加/删订阅、按房间查订阅"这类点查受益。
--
--   idx_live_record_file_room_status
--       服务「这个房间当前有没有在录的行」（room_id = ? AND status = ?）。
--
--   idx_live_record_file_start_time
--       服务容量巡检的**淘汰排序**（按 start_time 升序取最早的）。
--       这是全表排序里唯一能吃到索引的一条，且它正是"删最旧的"那条热路径。
--
-- 都是普通（非唯一）索引：同一个房间显然会有多行、同一时刻允许多场历史记录。
CREATE INDEX IF NOT EXISTS idx_live_record_sub_room_id
    ON live_record_sub (room_id);

--   idx_live_record_sub_group_room
--       服务「录播网页」的可见性推导：给定群号，取出它订阅过的全部房间号
--       （LiveRecordSubMapper#selectEverySubscribedRoomIds，条件是 group_id = ?）。
--       列顺序 group_id 在前 —— 等值条件打头，room_id 只用来覆盖索引本身。
--
--   idx_record_web_token_token  —— 唯一索引
--       服务「网页请求 → 这是哪个群」，条件是 token = ?，每次请求都走。
--       它必须**唯一**：查表是按 token 反查群号，两行同 token 就等于两个群串号。
--       （SQLite 里 NULL 不参与唯一约束，所以多个 group_id 为空的行走不了这条；
--        但代码只取/建一行，见建表处 group_id 的说明。）
--
--   idx_record_web_token_group_id
--       服务「这个群的令牌还在不在」（group_id = ?），只在取/重置令牌时走一次。
CREATE INDEX IF NOT EXISTS idx_live_record_sub_group_room
    ON live_record_sub (group_id, room_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_record_web_token_token
    ON record_web_token (token);

CREATE INDEX IF NOT EXISTS idx_record_web_token_group_id
    ON record_web_token (group_id);

CREATE INDEX IF NOT EXISTS idx_live_record_file_room_status
    ON live_record_file (room_id, status);

CREATE INDEX IF NOT EXISTS idx_live_record_file_start_time
    ON live_record_file (start_time);
