# XatiiBot
XatiiBot 是一个基于 Java 开发的 QQ 机器人，主要用于解析和分析 Bilibili 平台的内容，包括视频、直播和动态。该机器人能够自动识别聊天中的 Bilibili 链接，并返回相关内容的详细信息。

## 功能特点
- 视频解析 ：解析 Bilibili 视频链接，显示视频标题、简介、播放量、点赞数等信息
- 直播解析 ：解析 Bilibili 直播间链接，显示直播间标题、主播信息、观看人数等
- 动态解析 ：解析 Bilibili 动态链接，显示动态内容和发布者信息
- 短链接解析 ：支持解析 b23.tv 短链接
## 技术栈
- Spring Boot 3.4.4 ：作为应用程序的基础框架
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
   < version > 0.9.13.1-beta </ version >
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

- BilibiliAnalysisPlugin ：处理消息事件，识别并提取 Bilibili 链接
- BilibiliAnalysisImpl ：实现 Bilibili 内容解析的核心逻辑
- AdminService/AdminMapper ：处理管理员相关的数据库操作
## 使用方法
1. 确保已安装 Java 17 或更高版本
2. 配置数据库连接
3. 构建并运行项目：
   ```bash
   
   mvn clean package
   
   java -jar target/XatiiBot-2.0.0-beta.jar
   ```
4. 将机器人添加到 QQ 群中，当有人发送 Bilibili 链接时，机器人会自动解析并回复相关信息
## Bilibili-API
本项目使用了 Bilibili-API 库来与 Bilibili 平台交互。该 API 库的仓库地址：
### 请注意该依赖需自行添加，参考该仓库地址的引入方式
https://github.com/abcLiyew/BiliBili-API

## 开发者
- LiYehe ：项目主要开发者
## 许可证
请参阅项目中的 LICENSE 文件了解详情。