# XatiiBot
XatiiBot 是一个基于 Java 开发的 QQ 机器人，主要用于解析和分析 Bilibili 平台的内容，包括视频、直播和动态。该机器人能够自动识别聊天中的 Bilibili 链接，并返回相关内容的详细信息。

## 功能特点
- 视频解析 ：解析 Bilibili 视频链接，显示视频标题、简介、播放量、点赞数等信息
- 直播解析 ：解析 Bilibili 直播间链接，显示直播间标题、主播信息、观看人数等
- 动态解析 ：解析 Bilibili 动态链接，显示动态内容和发布者信息
- 短链接解析 ：支持解析 b23.tv 短链接
- 直播 / 动态订阅推送 ：为指定 B 站房间添加订阅，开播、下播、投稿与动态自动推送到群聊或私聊
- 群签到养成 ：群内提供「签到 / 查询 / 今日运势」互动，累计好感度并划分等级与态度
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
   < version > 0.9.28-beta </ version >
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
   < groupId > mysql </ groupId >
   < artifactId > mysql-connector-java </ artifactId >
   < version > 8.0.33 </ version >
</ dependency >

```

## 项目结构
项目主要包含以下组件：

- BilibiliAnalysisPlugin ：识别消息中的 Bilibili 链接，并分发给解析服务
- BilibiliAnalysisImpl ：Bilibili 视频、直播、动态内容解析的核心实现
- BiliBiliPushPlugins ：处理「添加订阅 / 取消订阅」指令，并定时推送开播、下播与投稿动态
- BiliConfigPlugins ：处理「设置cookie / cookie状态 / 清除cookie」与「设置代理 / 代理状态 / 清除代理」指令，管理动态推送所需的 B 站登录凭据与出口
- SignInPlugins ：处理「签到 / 查询 / 今日运势」指令，维护群内好感度
- PushInfoServiceImpl ：订阅的增删与推送逻辑，含管理员鉴权
- SignInRecordsServiceImpl ：签到数据读写与好感度结算
- BotAdminChecker ：统一的管理权限判定（订阅、Cookie 配置等敏感操作共用一份规则）
- LoadDSConfig ：启动时从数据库加载运行时配置，支持热更新
- AdminService/AdminMapper ：处理管理员相关的数据库操作
- mapper/* 与 resources/mapper/*.xml ：MyBatis-Plus 数据访问层
## 使用方法
1. 确保已安装 Java 17 或更高版本
2. 配置数据库连接
3. 构建并运行项目：
   ```bash
   
   mvn clean package
   
   java -jar target/XatiiBot-2.0.0-beta.jar
   ```
4. 将机器人添加到 QQ 群中，当有人发送 Bilibili 链接时，机器人会自动解析并回复相关信息

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

### 怎么配（二选一）

**方式一：聊天命令（推荐，改完即时生效、不用重启）**

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
> 2. 手工往 `admin` 表加一行：`qq_uid=<QQ号>`（`group_id` 留空表示全局，填群号表示只在该群有效）。
>
> 没有命令能往 `admin` 表加人 —— 这张表由机器人所有者自己维护，所以**表内即受信**。

- 分隔符认 `空格` / `换行` / `;`，键值对认 `:` 或 `=`（全角 `：` 也认）。
- **只发要更新的键即可**：新值会与已保存的合并（`SESSDATA` 过期时只发 `SESSDATA` 就行）。
- 解析出的键里一个 `SESSDATA`/`buvid3`/`buvid4` 都没有时会拒绝保存，避免把已有 Cookie 覆盖成垃圾。
- 命令消息里含凭据，机器人**处理完会尝试撤回该消息**（撤回失败会提示你手动撤回）。
  回复与日志**只出现键名，绝不出现值**。
- 保存后立刻生效，无需重启；下一轮动态推送（≤60s）就会用上。

**方式二：直接写数据库**（机器人没开机时可用）

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

> Cookie 会过期，失效后动态推送会重新报 `-352`，届时重新执行上面的命令即可。
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