# XatiiBot

XatiiBot 是一个基于 Java 开发的 QQ 机器人，主要用于解析和分析 Bilibili 平台的内容，包括视频、直播和动态。该机器人能够自动识别聊天中的 Bilibili 链接，并返回相关内容的详细信息。

[![release](https://img.shields.io/github/v/release/abcLiyew/XattiiBot?include_prereleases&sort=semver)](https://github.com/abcLiyew/XattiiBot/releases)
[![license](https://img.shields.io/github/license/abcLiyew/XattiiBot)](LICENSE)
[![java](https://img.shields.io/badge/JDK-17%2B-orange)](https://adoptium.net/)

> 📦 **下载**：到 [**Releases**](https://github.com/abcLiyew/XattiiBot/releases) 取 `XatiiBot-<版本>.jar`，
> **直接 `java -jar` 就能跑** —— 数据库、表、索引会在首次启动时自动建好，不需要预先准备任何东西。
> 详见下方[「使用方法」](#使用方法)。

## 功能特点
- 视频解析 ：解析 Bilibili 视频链接，显示视频标题、简介、播放量、点赞数等信息
- 直播解析 ：解析 Bilibili 直播间链接，显示直播间标题、主播信息、观看人数等
- 动态解析 ：解析 Bilibili 动态链接，显示动态内容和发布者信息
- 短链接解析 ：支持解析 b23.tv 短链接
- 直播 / 动态订阅推送 ：为指定 B 站房间添加订阅，开播、下播、投稿与动态自动推送到群聊或私聊
- 直播录播 ：订阅的主播开播自动录（flv 流式落盘、取用才转 mp4），全局容量自管理
  （超阈值先压缩、再清最旧），附只读录播网页；ffmpeg 缺失时后台自动下载
- 反刷屏（入站） ：群消息按类型做滑窗频率统计 + 相同消息全群去重，命中分级处置
  （默认仅私信告警机器人所有者），全部参数对话即可配置
- 发送熔断（出站） ：机器人自身的发送保险丝 —— 任何 bug / 设计缺陷导致向同一目标
  高频或重复发消息时，自动熔断 60 秒并私信告警所有者；**默认开启**
- 群签到养成 ：群内提供「签到 / 查询 / 今日运势」互动，累计好感度并划分等级与态度
- B 站凭据管理 ：私聊发「登录」用哔哩哔哩 App 扫码刷新 Cookie（仅机器人所有者），也支持管理员的
  「设置cookie / cookie状态 / 清除cookie」命令；Cookie 失效自动探测并提示

## 技术栈
- Spring Boot 3.5.7 ：作为应用程序的基础框架
- MyBatis-Plus ：用于数据库操作的增强工具
- Shiro ：QQ 机器人框架，用于处理消息事件
- Bilibili-API ：用于与 Bilibili 平台交互的 API 库
## 依赖关系
项目主要依赖如下：

```xml
<!-- Bilibili API 依赖 -->
< dependency >
    < groupId > com.esdllm </ groupId >
   < artifactId > bilibili-api </ artifactId >
   < version > 0.10.0-SNAPSHOT </ version >
</ dependency >

<!-- 扫码登录要用的二维码生成（上游把它声明成 scope=test，不传递，必须自己引） -->
< dependency >
   < groupId > com.google.zxing </ groupId >
   < artifactId > core </ artifactId >
   < version > 3.3.3 </ version >
</ dependency >

<!-- 数据库相关依赖 -->
< dependency >
   < groupId > org.xerial </ groupId >
   < artifactId > sqlite-jdbc </ artifactId >
   < version > 3.41.2.2 </ version >
</ dependency >

<!-- MyBatis-Plus 依赖 -->
< dependency >
   < groupId > com.baomidou </ groupId >
   < artifactId > mybatis-plus-spring-boot3-starter </ artifactId >
   < version > 3.5.9 </ version >
</ dependency >

<!-- 多数据源支持 -->
< dependency >
   < groupId > com.baomidou </ groupId >
   < artifactId > dynamic-datasource-spring-boot3-starter </ artifactId >
   < version > 4.3.1 </ version >
</ dependency >

<!-- 数据库连接池 -->
< dependency >
   < groupId > com.alibaba </ groupId >
   < artifactId > druid-spring-boot-3-starter </ artifactId >
   < version > 1.2.23 </ version >
</ dependency >

<!-- MySQL 驱动-->
< dependency >
   < groupId > com.mysql </ groupId >
   < artifactId > mysql-connector-j </ artifactId >
   < version > 8.0.33 </ version >
</ dependency >

```

## 项目结构
项目主要包含以下组件：

- BilibiliAnalysisPlugin ：识别消息中的 Bilibili 链接，并分发给解析服务
- BilibiliAnalysisImpl ：Bilibili 视频、直播、动态内容解析的核心实现
- BiliBiliPushPlugins ：处理「添加订阅 / 取消订阅」指令，并定时推送开播、下播与投稿动态
- BiliConfigPlugins ：处理「设置cookie / cookie状态 / 清除cookie」「设置代理 / 代理状态 / 清除代理」与「开关」指令，管理动态推送所需的 B 站登录凭据、出口与功能开关
- BiliLoginPlugins ：处理「登录」扫码登录（仅机器人所有者、仅私聊）与「登录状态」查询
- QrCodeUtils ：把登录二维码渲染成 PNG base64
- CookieUtils ：Cookie 解析与合并的**唯一口径**（扫码登录、手工配置两条写入路径共用）
- BiliSearchPlugins ：处理「搜视频 / 热搜 / 今日热门」三条只读查询指令
- CredentialGuard ：B 站凭据探测与缓存的统一入口（事件驱动 + 定时兜底）
- SignInPlugins ：处理「签到 / 查询 / 今日运势」指令，维护群内好感度
- AntiSpamPlugins ：反刷屏（入站）—— 每条群消息的分类、滑窗计数、相同消息去重与分级处置
  （告警 / 撤回 / 禁言），「反刷屏」系列命令对话配置
- SendGuardAspect ：发送熔断（出站）—— 一个 AOP 切面包住 shiro 全部发送方法的唯一汇入口
  `ActionHandler.action(..)`，按目标滑窗限频 + 重复内容熔断，零调用点改动
- LiveRecordPlugins / LiveRecordServiceImpl ：「录播订阅 / 录播列表 / 录播下载 …」命令与
  录制调度（开播自动起 ffmpeg、断流自查、容量压缩/清理）；RecordFileServer 是内置只读文件服务，
  RecordWebController 提供录播网页
- FfmpegProvider ：ffmpeg 运行时获取与探活（配置路径 → `./bin/ffmpeg` 缓存 → PATH →
  后台自动下载，原子落盘），录播与转码共用
- PushInfoServiceImpl ：订阅的增删与推送逻辑，含管理员鉴权
- SignInRecordsServiceImpl ：签到数据读写与好感度结算
- BotAdminChecker ：统一的管理权限判定（订阅、Cookie 配置等敏感操作共用一份规则）
- LoadDSConfig ：启动时从数据库加载运行时配置，支持热更新
- DbInitializer ：启动时（**任何 bean 实例化之前**）执行 `schema/<数据源名>.sql` ——
  补建库文件目录、建表、补缺列、建索引。这是「`java -jar` 开箱即用」的实现
- AdminService/AdminMapper ：处理管理员相关的数据库操作
- mapper/* 与 resources/mapper/*.xml ：MyBatis-Plus 数据访问层
- resources/schema/sqlite.sql ：SQLite 的建表 / 补列 / 建索引脚本，由 `DbInitializer` 在启动时执行。
  只允许写 `CREATE ... IF NOT EXISTS` 和 `ALTER TABLE ADD COLUMN`（脚本每次启动都跑，所以禁止 DROP/DELETE/UPDATE）；
  **给已有的表加列 = 直接改这里的 `CREATE TABLE` 语句**，期望列是从它解析出来的，不用另外维护列清单

## 可选功能开关（config 表）

以下功能**默认关闭**，**两种改法都可**，且**热改即时生效、不用重启**。
之所以默认关：它们每开一个都会让「发一条视频/直播链接」的 B 站请求数从 1 次变成 2 次，
而 B 站的风控是**请求密度敏感**型（线上曾因请求过密吃到 `-412`）。

| key | 值 | 效果 | 额外前提 |
|---|---|---|---|
| `biliAnalysisWithSummary` | `true` | 视频解析附带**AI 摘要**（总纲 + 前 3 个分段大纲） | ⚠️ **必须已配置 `biliCookie`**：该端点硬要求登录，无 Cookie 时即使开着也不会生效 |
| `biliAnalysisWithComments` | `true` | 视频解析附带**热评** Top3（赞数 + 昵称） | 无（匿名可用） |
| `biliLiveWithMasterInfo` | `true` | 直播解析附带**主播粉丝数 + 粉丝牌** | 无（匿名可用） |
| `biliCredentialCheckHours` | 数字，如 `6` | 凭据兜底探测间隔（小时）；`0` 或负数 = 关闭兜底 | 无（无 Cookie 时不探测） |

### 改法一：聊天命令（推荐）

**只允许 `admin` 表里的管理员**使用（与「设置cookie / 设置代理」同一权限口径）。

```
开关                                   ← 列出全部开关及当前值
开关 热评 开                           ← 改一项，改完即时生效
开关 摘要 关
开关 动态源 follow                      ← 切动态推送数据源（三个取值见下表）
开关 探测间隔 12                        ← 凭据兜底探测间隔（小时），0 = 关闭
开关 热评                              ← 只看这一项的详情（含配置键、可选值含义）
```

名称可用中文主名或别名：`热评`/`评论`/`comments`、`主播`/`直播`/`live`、
`摘要`/`ai`/`summary`、`动态源`/`动态`/`source`、`探测间隔`/`探测`/`interval`。
布尔项的值认 `开`/`关`（也接受 `on`/`off`、`true`/`false`、`1`/`0`、`启用`/`禁用`）。

**「动态源」的三个取值是什么意思**（机器人面板与详情里也会一并列出来）：

| 值 | 含义 |
|---|---|
| `auto` | 先试空间流（按 uid 逐个拉），被判风控就自动切关注流 —— **默认值，一般不用改** |
| `follow` | 始终走关注流：一轮 1 次请求覆盖全部订阅；⚠️ 要求配 Cookie 的那个账号**已关注**被订阅的 UP |
| `space` | 始终按 uid 拉空间动态（出口没被 B 站单独封的环境用这个） |

> 为什么会有这三种取值：见下面的《动态数据源：feed/space 与「关注流」》一节。

> 面板里若显示 `关（值无效：xxx）`，说明 config 表里的值既不是开也不是关
> —— 在 fail-closed 判定下它按**关**处理，请用命令或 SQL 重新写成合法值。

### 改法二：直接写数据库（机器人没开机、或要批量改时）

```sql
INSERT INTO config(key, value) VALUES ('biliAnalysisWithSummary', 'true');
-- 已有该行时改用 UPDATE（注意应用实际读的是 ./resources/xatiiBot.db）
```

布尔开关的判定是 **fail-closed** 的：只有写成 `true` / `1` / `on` / `yes` 才算开，
键不存在、值为空、拼错（如 `ture`）一律按**关**处理 —— 免得"配置写错反而把请求量放大"。
开关状态可在启动日志、改配置时的回显、或直接发「开关」命令确认（AI 摘要那项还会额外报一句 Cookie 有没有配）。

## 防刷屏双保险：反刷屏（防别人）+ 发送熔断（防自己）

两套机制方向相反、互不替代：

| | 反刷屏 AntiSpam（入站） | 发送熔断 SendGuard（出站） |
|---|---|---|
| 防谁 | **群友**刷屏（斗图轰炸、病毒转发、恶意调机器人） | **机器人自己**刷屏（推送循环 bug、设计缺陷） |
| 拦截点 | 每条群消息（`@AnyMessageHandler`） | 全部发送调用的唯一汇入口（AOP 切 `ActionHandler.action`） |
| 默认状态 | **关**（「反刷屏 开」启用） | **开**（保险丝常开，正常业务远低于阈值零感知） |
| 命中动作 | 分级：alert 仅告警 → recall +撤回 → ban +禁言 10 分钟 | 丢弃消息并熔断该目标 60 秒，自动恢复 |
| 告警 | 私信机器人所有者（`admin` 表 `group_id` 为空那条），5 分钟冷却 | 同左 |

### 反刷屏：对话配置（推荐）

**只允许 `admin` 表里的管理员**使用（与「设置cookie」同一权限口径），全部即时生效：

```
反刷屏                                ← 状态面板（总闸/动作/阈值/告警对象一览）
反刷屏 开 ｜ 反刷屏 关                 ← 总闸
反刷屏 动作 alert                     ← 处置级别：alert / recall / ban（后两者要求机器人是群管理员）
反刷屏 设置 窗口 60                   ← 滑窗秒数（5~3600）
反刷屏 设置 去重 3                    ← 窗口内相同消息达到几条触发（2~100）
反刷屏 设置 冷却 5                    ← 告警冷却分钟数（1~1440）
反刷屏 设置 监控群 123,456            ← 只监控这些群；「全部」= 所有群
反刷屏 设置 单人阈值 image:6,text:12  ← 单人×类型阈值表
反刷屏 设置 全群阈值 image:20,text:40 ← 全群×类型阈值表
```

类型取值：`image / forward / video / record / share / face / text / command / other`
（`command` = @ 机器人的消息，单独一套阈值防恶意调机器人）。
默认阈值：单人 `image:6, forward:2, video:3, record:5, share:3, face:8, text:12, command:5, other:15`；
全群 `image:20, forward:5, video:10, record:15, share:8, face:30, text:40, command:15, other:50`。
对应配置键：`antiSpamEnabled / antiSpamAction / antiSpamWindowSeconds / antiSpamDupThreshold /
antiSpamAlertCooldownMinutes / antiSpamGroups / antiSpamUserLimits / antiSpamGroupLimits`。

> 建议先保持 `alert` 跑一阵看误报，可信再升 `recall` —— 撤回/禁言都要求机器人是目标群的管理员。
> 机器人自己、群主/群管理员、`admin` 表白名单永远豁免检测。

### 发送熔断：无需操作，了解即可

- **规则**（按目标各一个 60 秒滑窗）：阈值**按消息类别**分档（调用方插件经调用栈自动识别，
  30 个发送点零改动）——

  | 类别 | 涵盖 | 默认阈值（条/60s/目标） | 配置键 |
  |---|---|---|---|
  | 推送 push | 开播/下播/动态/投稿（自动触发，风险最高） | **4** | `sendGuardLimitPush` |
  | 签到/运势 signin | 签到、查询、今日运势（指令驱动回复） | 12 | `sendGuardLimitSignin` |
  | 今日老婆 wife | 今日老婆（指令驱动回复） | 10 | `sendGuardLimitWife` |
  | 其它 other | 查询/配置/录播等未归类发送 | 10 | `sendGuardLimitOther` |
  | 私聊上限帽 | 任何类别的私聊目标 | 6 | `sendGuardPrivateLimit` |

  完全相同内容 ≥3 条 ⇒ 重复熔断（与类别无关）。当年"每 10 秒发一条下播通知"的事故
  （稳态 6 条/分钟），频率 + 重复两条规则都能在第 3~5 条掐断。
- **熔断** = 之后 60 秒内发往该目标的消息一律丢弃，自动恢复；触发时私信告警所有者
  （含类别），熔断解除时日志记录"挡了多少条"。
- **被拦消息不抛异常**：返回与发送失败同形的 `retcode=-1` 结果，业务代码无感知。
- **fail-safe**：熔断器自身任何异常一律放行 —— 宁可不熔断，绝不误伤正常发送。
- 其它配置键（热更，一般不用动）：`sendGuardEnabled`（默认开）/ `sendGuardWindowSeconds`（60）/
  `sendGuardDupThreshold`（3）/ `sendGuardCircuitSeconds`（60）/ `sendGuardAlertCooldownMinutes`（5）。
  ⚠️ `sendGuardGroupLimit` 已废弃（2.0.3 起按类别限流）。

## 直播录播

订阅的主播开播自动录，录 **flv**（流式可写、断电可播），取用才 `-c copy` 转 mp4；
ffmpeg 退出后 worker 会**再查一次开播状态**区分"网络抖动断流"与"主播下播"，避免一场录成碎片。

```
录播订阅 22603245            ← 给本群添加录播订阅（管理员）
录播订阅列表                 ← 本群订阅一览
录播取消订阅 22603245
录播开关 22603245 关         ← 临时停录某房间，不删订阅
录播列表 [房间号]            ← 本群可见的录播文件（一场一行，fid 标识）
录播下载 <fid>               ← 转 mp4 并交付（自动选 URL/本地路径/base64）
录播保留 <fid>               ← 标记保留：容量清理时跳过
删除录播 <fid>               ← 删记录同时删文件目录
录播状态                     ← 正在录 / 容量 / ffmpeg 状态一览
录播整理                     ← 立即跑一次容量管理（压缩→清理）
录播网页                     ← 发一个带令牌的网页链接：本群订阅录播的浏览/播放/下载
```

容量自管理（全局维度，`live_record_file` 汇总）：总量超 `biliRecordCompressTotalMb`（默认 15GB）
先压缩老文件（降分辨率 + CRF，**只降不升**）；仍超 `biliRecordHardTotalMb`（默认 25GB）删最早的
（`keep=1` 保留的除外）。主要配置键：`biliRecordEnabled`（录播总闸）/ `biliRecordDir`（落盘目录）/
`biliRecordMaxConcurrent`（同录上限）/ `biliRecordDeliveryMode`（`auto/local/url/base64`）/
`biliRecordWebEnabled` + `biliRecordWebBaseUrl`（录播网页与对外基址）。

> ffmpeg 不用自己装：`FfmpegProvider` 启动时探活「配置路径 → `./bin/ffmpeg` 缓存 → PATH」，
> 都没有就后台自动下载静态构建（原子落盘，下好前录播报"ffmpeg 不可用"而不是写坏文件）。

## 使用方法

### 方式一：直接用 Release 里的 jar（推荐）

1. **装 JDK 17 或更高版本**：
   ```bash
   java -version
   ```
   ⚠️ 项目按 **Java 17** 编译（`maven.compiler.release=17`）。17 编出来的在 21/25 上能跑，
   反过来不行 —— 所以别低于 17。

2. **下载 jar**：[Releases](https://github.com/abcLiyew/XattiiBot/releases) → `XatiiBot-<版本>.jar`。

3. **直接启动，不需要预先准备任何东西**：
   ```bash
   java -jar XatiiBot-<版本>.jar
   ```
   首次启动会自动做完这些：
   - 建好 `./resources/` 目录和 `./resources/xatiiBot.db`（SQLite，**不用单独装数据库**）；
   - 建好全部表（`admin` / `config` / `push_info` / `sign_in_records` /
     `live_record_sub` / `live_record_file` / `record_web_token`）与索引；
   - 日志写到 `./logs/xatiiBot.log`（全量）和 `./logs/xatiiBot-error.log`（只 WARN/ERROR）。

   之后再启动是**幂等**的：只补缺的表、缺的列，**不碰已有数据**。

   ⚠️ **要在有写权限的目录里启动** —— 库、日志、临时图都是按「当前工作目录」找相对路径的。
   想换位置就改 `application.yaml` 里的 `spring.datasource.dynamic.datasource.sqlite.url`。

4. **接上 NapCat / OneBot v11**：机器人开 `2233` 端口，默认去连 `ws://127.0.0.1:3001`。
   端口和口令来自 `application.yaml` 的 `server.port` / `shiro.ws.*`，也可以命令行覆盖（优先级最高）：
   ```bash
   java -jar XatiiBot-<版本>.jar \
     --shiro.ws.client.url=ws://127.0.0.1:3001 \
     --shiro.ws.access-token=<你的 token>
   ```

5. **配 B 站 Cookie**（**只影响动态推送**；不配也能用链接解析、订阅、签到）：
   私聊机器人发「登录」扫码即可 —— 见下方「配置 B 站 Cookie」一节。

6. 把机器人拉进群 —— 有人发 B 站链接就会自动解析。

> Linux 上还要装 fontconfig，否则动态推送的图渲染不出来（通知不会丢，会降级成纯文字）：
> 见下方「⚠️ Linux 部署必装：字体」一节。

### 方式二：自己构建

```bash
mvn clean package                       # 产物：target/XatiiBot-<版本>.jar
java -jar target/XatiiBot-<版本>.jar
```

构建要点（都是踩过的坑）：
- 依赖里有一个**私有库** `com.esdllm:bilibili-api`（不在 Maven Central）—— 构建前得先能拿到它，
  见下方「Bilibili-API」一节；
- **用 JDK 17**；
- **Lombok 必须 ≥ 1.18.42**（1.18.34 在 JDK 25 上会抛 `ExceptionInInitializerError`），
  且 `pom.xml` 里要显式配 `maven-compiler-plugin` 的 `<annotationProcessorPaths>`
  —— JDK 23 起 javac 不再从 classpath 自动发现注解处理器，不配就整片「找不到符号」。

## ⚠️ Linux 部署必装：字体（否则动态推送的图发不出来）

动态推送要把抓到的动态渲染成**长图**再转 base64 发给 QQ。渲染用 Java2D，
字体取 `bilibili-api` **jar 内置**的 Noto Sans SC 子集 —— **但这仍然要求系统装了 fontconfig**：
Java 在 Linux 上无法绕过平台字体管理器，`Font.createFont` 内部同样会去读 fontconfig。

没装的后果（2026-09-14 真机踩到，Debian 11 最小系统）：

```
WARN  FontRegistry : 动态长图渲染：内置字体加载失败（...），回退到系统字体
ERROR SimpleAsyncUncaughtExceptionHandler : Unexpected exception occurred invoking async method
Caused by: java.lang.RuntimeException: Fontconfig head is null, check your fonts or fonts configuration
```

⚠️ 注意最后这个是 **`InternalError`（`Error`）而不是 `Exception`** —— 用 `catch (Exception)` 兜不住，
异常会一路穿出异步方法，**整轮推送在「发消息」之前就中断，用户什么都收不到**。

**修复**（约 5MB）：

```bash
apt-get install -y fontconfig fonts-dejavu-core
fc-list | wc -l          # 验证：> 0 即可
```

机器人侧也做了兜底（`renderDynamicImage`）：渲染失败一律降级为**纯文字推送**，
不再整条丢失；日志会直接打出上面这条修复命令。所以**即使忘了装字体，通知也不会丢**，只是没有图。

## 配置 B 站 Cookie（动态推送必需）

**动态推送**依赖 B 站桌面端动态接口 `x/polymer/web-dynamic/v1/feed/space`。该接口匿名已经过不去，
实测（2026-09-13）：

| 请求方式 | 结果 |
|---|---|
| 不带任何 Cookie | HTTP 412（风控页） |
| 带匿名指纹 `buvid3/buvid4` | HTTP 200 但业务码 `-352`（风控） |
| 再补 `web_location` + `dm_img_*` 客户端指纹参数 | 仍 `-352` |
| 换代理出口 IP | 无效（代理出口反而直接 412） |

所以必须注入**真实登录 Cookie**（浏览器里的 `SESSDATA` 等）。机器人启动时会从数据库 `config`
表读取该 Cookie 并交给 bilibili-api（`HttpPolicy.setCookie`）。

### 先说清楚：扫码登录 和 手工配 Cookie 是**两条并存的路**，不是"改用哪个"的关系

两者最终写的都是**同一个配置键** `config.biliCookie`，下游完全一样
（`LoadDSConfig` 读出来 → `HttpPolicy.setCookie`）。差别只在**凭据从哪来**：

| | `登录`（扫码） | `设置cookie`（手工粘贴） |
|---|---|---|
| 凭据来源 | 机器人自己向 B 站申请二维码，你用 App 扫 → 机器人拿到凭据 | 你从浏览器 F12 里复制整串 Cookie 贴给它 |
| 什么时候用它 | **Cookie 过期了**，手上没浏览器 / 懒得翻 F12 | 机器人跑在你够不到的地方，或要一次性灌入完整 Cookie |
| 权限口径 | **仅机器人所有者**（`admin` 表里 `group_id` 为空的那位），且**仅私聊** | `admin` 表白名单，群聊私聊都行 |
| 落点 | `config.biliCookie`，**增量合并**（保留已有 `buvid3/buvid4`） | 同上 |

> ⚠️ **两个权限口径是刻意不同的，别"顺手统一"**：
> - `设置cookie` 是**配置动作** —— 管理员交出的凭据是他**自己已经持有**的；
> - `登录` 是**授权转移** —— 二维码**谁扫到，机器人就以谁的账号出站**。
>   所以它只给所有者用、只允许私聊（发到群里等于把"谁能控制机器人出站身份"交给全群，
>   而且事后管不了是谁扫的）。

### 怎么配（三选一）

**方式一：扫码登录（最省事 —— 不用碰浏览器、不用重启）**

```
登录                                   ← 私聊发一张二维码，用【哔哩哔哩 App】扫 → 自动写入 Cookie
登录状态 / 查询登录状态 / 凭据状态      ← 问服务端"当前这枚凭据还算不算数"（会发一次请求）
```

- `登录` **仅机器人所有者 + 仅私聊**（原因见上面那条注意事项，不是保守，是二维码的语义决定的）；
  `登录状态` 群聊私聊都能用，但同样只认所有者。
- 二维码 3 分钟有效；**期间再发一次「登录」会作废前一张** —— 否则先发的那轮醒来后
  会把后一轮刚拿到的凭据覆盖掉。整个等待过程是异步的，不会卡住其它消息。
- 写库时是**合并**语义：手工配过的 `buvid3/buvid4` 会保留
  （缺了的话机器人每次出站都要额外领一次匿名指纹，纯浪费请求）。
- 立即生效，无需重启；下一轮动态推送（≤60s）就会用上。
- 群聊里发「登录」会被拒绝并告诉你原因。回复与日志**只出现键名，绝不出现值**。

**方式二：聊天命令手工粘贴 Cookie（改完即时生效、不用重启）**

机器人内置以下命令，**只允许 `admin` 表里的管理员**使用：

```
设置cookie buvid3:xxxx buvid4:yyyy SESSDATA:zzzz
设置cookie
  buvid3:xxxx
  buvid4:yyyy
  SESSDATA:zzzz
设置cookie SESSDATA=zzzz; bili_jct=xxxx     ← 直接贴浏览器的整串 Cookie 也行

cookie状态                                  ← 查看当前生效状态
清除cookie                                  ← 清掉，回到只用匿名指纹
```

> **权限说明**：Cookie 是**全局**配置（换了它会影响所有群/所有订阅的推送），
> 因此判定与 QQ 群的平台身份**完全脱钩**：**只看 `admin` 表白名单**，
> **群主 / 群管理员都不算**，**私聊也不再默认放行**（否则任何能给机器人发私信的人都能改全局凭据）。
>
> 加人方式（任选其一）：
> 1. `application.yaml` 里设置 `bot.admin: <QQ号>`，启动时会自动登记进 `admin` 表（`group_id` 留空＝全局管理员）；
> 2. 手工往 `admin` 表加一行：`qq_uid=<QQ号>`。
>
> 🔴 **这一档只认 `qq_uid`，`group_id` 目前不参与判定**：`isBotAdmin` 只按 `qq_uid` 查
> （见 `BotAdminChecker#isBotAdmin`），所以只要 `qq_uid` 命中，**哪怕 `group_id` 填了群号，
> 这个人照样拿到「改全局 Cookie / 代理 / 开关」的权限**。
> ⇒ 现阶段**只往 `admin` 表加 `group_id` 留空的记录**。填群号并不会把权限限制在那个群里 ——
> "按群授权"的收窄语义目前没实现，这是已知缺陷，修好之前别拿它当"限权"用。
>
> 没有命令能往 `admin` 表加人 —— 这张表由机器人所有者自己维护，所以**表内即受信**。

- 分隔符认 `空格` / `换行` / `;`，键值对认 `:` 或 `=`（全角 `：` 也认）。
- **只发要更新的键即可**：新值会与已保存的合并（`SESSDATA` 过期时只发 `SESSDATA` 就行）。
- 解析出的键里一个 `SESSDATA`/`buvid3`/`buvid4` 都没有时会拒绝保存，避免把已有 Cookie 覆盖成垃圾。
- 命令消息里含凭据，机器人**处理完会尝试撤回该消息**（撤回失败会提示你手动撤回）。
  回复与日志**只出现键名，绝不出现值**。
- 保存后立刻生效，无需重启；下一轮动态推送（≤60s）就会用上。

**方式三：直接写数据库**（机器人没开机时可用）

1. 浏览器登录 B 站 → F12 → Network → 随便点一个 `api.bilibili.com` 请求 →
   Request Headers 里的 `Cookie` **整串**复制（至少包含 `SESSDATA`）。
2. 写入 `config` 表（注意应用实际读的是 `./resources/xatiiBot.db`，不是根目录那个副本；
   时间列名是 `creat_time`，不是 `create_time`）：

```sql
INSERT INTO config (key, value, creat_time, update_time, is_delete)
VALUES ('biliCookie', 'SESSDATA=xxx; bili_jct=xxx; ...', 0, 0, 0);

-- 已有该行时改为更新
-- UPDATE config SET value = '新的完整Cookie', update_time = 0 WHERE key = 'biliCookie';
```

3. 重启机器人。启动日志里会打印一行 HTTP 策略摘要，确认 `Cookie=已注入(...)`；
   若显示 `仅匿名指纹`，说明键名写错或值为空。

> Cookie 会过期，失效后动态推送会重新报 `-352`。
> **不用重启、也不用去翻浏览器** —— 私聊发一句「登录」扫码即可（见方式一）；
> 想先确认到底是不是过期了，发「登录状态」，它会去问服务端要权威判定。
> 机器人**不会**把 Cookie 打进日志（只打印键名，值一律打码）。

**未配置 Cookie 时**：直播推送、B 站链接解析等其它功能不受影响，只有动态推送拉不到列表。

## 动态数据源：feed/space 与「关注流」

动态推送有两条数据源，机器人会**自动选择**：

| 数据源 | 端点 | 特点 |
|---|---|---|
| 空间动态 | `x/polymer/web-dynamic/v1/feed/space` | 「按 uid 查某个 UP 的动态」，每个订阅 1 次请求 |
| **关注流** | `x/polymer/web-dynamic/v1/feed/all` | 「我关注的人的最新动态」，**一轮 1 次请求覆盖全部订阅** |

**2026-09-14 真机实测**：B 站 WAF 会**按客户端封禁** feed/space —— 同一台机器、同一枚有效 Cookie：

```
x/frontend/finger/spi                    → 200 / code=0
x/polymer/web-dynamic/v1/feed/space      → 412 / {"code":-412,"message":"request was banned"}   ← 只有这条被拒
x/polymer/web-dynamic/v1/feed/all        → 200 / code=0（15 万字节真实数据）
```

换 buvid、换请求头形状、拉长请求间隔都**无效**（不是频率问题，是这条路被封）。所以机器人一旦发现
feed/space 被判风控，就会**自动切到关注流**并一直用它（重启后重新探测）：

```
feed/space 被判风控（...HTTP 412，命中 B 站风控...）→ 本轮起改用「关注流」数据源（一轮 1 次请求覆盖所有 UP）。
注意：关注流只包含该 B 站账号【已关注】的 UP，未关注的订阅推不到。
```

### ⚠️ 用关注流有一个前提：去关注那些 UP

关注流返回的是**登录账号所关注**的 UP 的动态。所以请用配 Cookie 的那个 B 站账号，
把**被订阅的 UP 全部关注一遍**（用直播房间号对应的 UP 主页关注即可）。

没关注的话机器人会在日志里提醒（同一批 uid 最多 30 分钟提醒一次）：

```
关注流里没有这些被订阅的 UP：[497078180, 3546774476163227] —— 关注流只包含「该 B 站账号已关注」的 UP，
若其中有没关注的，请用该账号去关注（否则这些订阅推不到）；已关注的 UP 只是最近没发动态时也会不出现，属正常。
```

怎么确认当前在用哪条源：日志里出现 `正在获取关注流:` 就是关注流模式，出现 `正在获取动态列表:` 则是空间动态模式。

**手动指定数据源**（可选）：`config` 表加一行 `key='biliDynamicSource'`、`value='follow' | 'auto' | 'space'`，
改完即时生效。默认 `auto`（先试空间动态，被判风控则自动改用关注流）。
实测那种"路径被 -412 封禁"的机器把它设成 `follow`，重启后就不会先白撞一次 `feed/space` 了。

## Cookie 正确却仍然持续 412？——按「路径级封禁」处理

这种情况先别怀疑 Cookie。按顺序看两行日志：

**1. 启动时的策略摘要**（`LoadDSConfig`）
```
B 站 HTTP 策略：HttpPolicy{..., 代理=直连, Cookie=已注入(buvid3,buvid4,SESSDATA)}
```

**2. 第一次出站时打印的「出站身份」**（bilibili-api 0.9.23 起，仅在身份变化时打印）
```
出站身份：Cookie 键=[buvid3,buvid4,SESSDATA]，设备指纹来源=登录 Cookie，UA="..."
```

判读方式：

| 现象 | 结论 |
|---|---|
| `出站身份` 那行显示 **未携带任何 Cookie** | 是拼装/注入问题，与风控无关（检查 config 表是不是写进了另一个库文件） |
| 键名齐全，但仍持续 412 | **请求形状没问题** —— 是「这条路径对这台机器被拒」，见下文 |
| 失败日志里是 `code=-352` | Cookie 已失效（`SESSDATA` 过期），重新发一次 `设置cookie SESSDATA:...` |

判定"是哪条路径被拒"最省事的办法，是在**跑机器人的那台机器**上直接 curl 几次
（`<Cookie>` 换成你配的那串；**每条之间隔 20 秒以上**，避免踩请求密度）：

```bash
CK='SESSDATA=xxx; buvid3=xxx; buvid4=xxx'
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36'
FEED='https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=497078180&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote&platform=web&build=735002902680334849'
ALL='https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=all&page=1&platform=web&build=735002902680334849'

curl -s -o /dev/null -w 'spi  =%{http_code}\n' 'https://api.bilibili.com/x/frontend/finger/spi'
curl -s -o /dev/null -w 'space=%{http_code}\n' -H "User-Agent: $UA" -H 'Accept: application/json, text/plain, */*' \
  -H 'Referer: https://space.bilibili.com/497078180/dynamic' -H "Cookie: $CK" "$FEED"
curl -s -o /dev/null -w 'all  =%{http_code}\n' -H "User-Agent: $UA" -H 'Accept: application/json, text/plain, */*' \
  -H 'Referer: https://t.bilibili.com/' -H "Cookie: $CK" "$ALL"
```

> **2026-09-14 在某台中国香港 VPS 上的实测（三条同时刻对照）**：
> `spi = 200`、**`space = 412`**（正文 `{"code":-412,"message":"request was banned"}`）、**`all = 200/code=0`**。
> 也就是说不是"Cookie 失效"，也不是"整台机器被封"，而是 **`feed/space` 这条路径对该客户端被明确封禁**
> （换 buvid、换请求头形状、拉长间隔都无效）。此时机器人会自动切到关注流 —— 见上一节。
> 另外 412 还有**惩罚窗口**行为：1 秒内对 `api.bilibili.com` 连发约 3 个请求也会触发，
> 之后一段时间的请求继续 412，安静 30~60 秒后恢复。
> **排障时别把"限流"当成"Cookie 失效"**，也**别用"多试几次"的方式验证** —— 那只会把窗口续期。

### 出路：给 B 站请求配一个代理

```
设置代理 127.0.0.1:7890      ← 让 B 站请求走这个 HTTP 代理（即时生效）
代理状态                     ← 看当前出口、Cookie 键、最小请求间隔
清除代理                     ← 恢复直连
```

- 只支持 `host:port`（也容忍 `http://host:port`）；**不支持带账号密码的代理**，
  写成 `user:pass@host:port` 会被明确拒绝（本库的代理参数只有 host/port），
  需要认证就在本机再起一层转发。
- 等价配置项：`config` 表 `key='biliProxy'`、`value='host:port'`。
- 代理只是把出站流量绕到另一个出口，**不改变请求内容**。

### 机器人侧的自我保护（无需操作，了解即可）

| 机制 | 说明 |
|---|---|
| 轮询间隔 60 秒 | `BiliBiliPushPlugins.dynamicPush`，对出口最友好又足够及时的节奏 |
| **动态 feed 用与真实网页一致的请求头** | `Accept: application/json` + `Referer: https://space.bilibili.com/<uid>/dynamic`（0.9.24 起）。库默认发的是"文档型 Accept + 站点根 Referer"，那是"打开页面"的形状；形状不一致不会报错，但会让请求在低信誉出口上更像非浏览器客户端 |
| 有 Cookie 时关闭「风控轮换身份」 | 轮换改不了身份（Cookie 里的 buvid 优先），只会多打一次指纹接口并立刻重试，等于给惩罚窗口续期 |
| 有 Cookie 时最小请求间隔 400ms → 1200ms | 多个 UP 的请求不再挤成一瞬间，不形成小突发 |
| 仅带 `buvid` 的 Cookie 会跳过匿名指纹领取 | 领来的值会被用户 Cookie 覆盖，纯白跑一趟，且正好排在业务请求前几百毫秒（最敏感的连发形态） |
| 失败后冷却 1 → 2 → 4 → 8 分钟 | 让惩罚窗口有机会过期；上限 8 分钟**小于**新鲜窗口 15 分钟，所以退避不会变成漏推 |
| 新鲜窗口 15 分钟 + 末次动态去重 | 冷却/重启期间的动态恢复后仍会补推，且不会重复刷屏 |

## Bilibili-API
本项目使用了 Bilibili-API 库来与 Bilibili 平台交互。该 API 库的仓库地址：
### 请注意该依赖需自行添加，参考该仓库地址的引入方式
https://github.com/abcLiyew/BiliBili-API

## 开发者
- 饿死的流浪猫 ：项目主要开发者
## 许可证
请参阅项目中的 LICENSE 文件了解详情。