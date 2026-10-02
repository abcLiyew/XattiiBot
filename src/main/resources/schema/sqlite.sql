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
