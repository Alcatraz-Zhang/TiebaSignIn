<div align="center"> 
<h1 align="center">贴吧签到助手</h1>
<img src="https://img.shields.io/github/issues/LuoSue/TiebaSignIn-1?color=green">
<img src="https://img.shields.io/github/stars/LuoSue/TiebaSignIn-1?color=yellow">
<img src="https://img.shields.io/github/forks/LuoSue/TiebaSignIn-1?color=orange">
<img src="https://img.shields.io/github/license/LuoSue/TiebaSignIn-1?color=ff69b4">
<img src="https://img.shields.io/github/languages/code-size/LuoSue/TiebaSignIn-1?color=blueviolet">
</div>

# 简介

每日自动帮你签到所有关注的贴吧，优先使用客户端（手机端）接口（BDUSS 鉴权，经验值更高），客户端失败时自动回退到 web 端接口（需 BDUSS + STOKEN 鉴权），支持超过 200 个贴吧签到。

# 功能

+ 贴吧签到（支持 200 个以上，实际取决于你关注的贴吧总数）

+ **优先使用客户端（手机端）接口签到**，经验值更高；客户端失败时自动回退到 web 端接口兜底。

+ **多轮重试**：签到过快、需验证等暂时性失败会在后续轮次自动重试（最多 5 轮）。

+ **智能处理签到结果**：已签到、贴吧失效等非重试场景不再被算作失败，只会出现在对应的统计项里（新签到 / 已签到跳过 / 失败 / 失效），不会浪费重试配额。

+ 支持推送运行结果至微信（通过 PushPlus）

# 使用方法

## 1. fork 本项目

### 必须检查的仓库设置

1. Settings -> Actions -> General -> Workflow permissions：选择 "Read and write permissions"（以允许 GITHUB_TOKEN push）。

2. 确保仓库没有被 Archived（Settings -> General -> Danger Zone: Archive repository）。

## 2. 获取 BDUSS

在网页中登录上贴吧，然后按下 `F12` 打开调试模式，在 `cookie` 中找到 `BDUSS`，并复制其 `Value` 值。

![](./assets/获取BDUSS.gif)

## 3. 获取 STOKEN

> **为什么需要 STOKEN？**
> 百度已升级接口鉴权策略，仅凭 BDUSS 会返回 `is_login=0`（未登录），导致签到全部失败。需要同时携带 STOKEN 才能正常使用。

在同一个调试界面的 `cookie` 列表中找到 `STOKEN`，并复制其 `Value` 值。

> **⚠️ 注意**：cookie 列表里可能出现两个 `STOKEN`，域分别是 `tieba.baidu.com` 和 `passport.baidu.com`。
> **请选择域为 `tieba.baidu.com` 的那个**，passport 域下的 STOKEN 作用域不同，发给贴吧接口无效。

**STOKEN 的有效期与 BDUSS 类似（月～年级别），复制一次可以长期使用，不需要定期维护。**
只有在以下情况下才需要重新获取：
- 修改了百度账号密码
- 主动退出了贴吧/百度登录
- 百度安全系统检测到异常并强制重置了 session
- workflow 日志出现"未登录"提示时

## 4. 将 BDUSS 和 STOKEN 添加到仓库的 Secrets 中

Name | Value | 说明
-|-|-
BDUSS | xxxxxxxxxxx | 必填
STOKEN | xxxxxxxxxxx | 必填（不填将因百度鉴权升级而签到失败）
SCKEY | xxxxxxxxxxx | 选填，PushPlus token，用于推送签到结果到微信

将上面获取到的值粘贴到 `Settings -> Secrets and variables -> Actions -> New repository secret` 中。

![](./assets/添加BDUSS.gif)

> **老用户注意**：如果你之前只配置了 BDUSS，需要额外添加 STOKEN，否则签到会因百度鉴权升级而失败（日志显示"未登录"）。

## 5. 开启 actions

默认 `actions` 是处于禁止的状态，需要手动开启。

![](./assets/开启actions.gif)

## 6. 第一次运行 actions

+ 自己提交一次 `push`。

将 `run.txt` 中的 `flag` 由 `0` 改为 `1`

```patch
- flag: 0
+ flag: 1
```

![](./assets/运行结果.gif)

## 成功了

每天早上 `6:30` 将会自动进行签到

## 添加 PushPlus 推送

需在 Secrets 中添加 PushPlus 的 `SCKEY`（即你的 PushPlus token），格式如下

Name | Value
-|-
SCKEY | xxxxxxxxxx

## 2026-5-30

+ 代码重构

+ 修改 API 以及签到策略

## 2020-11-01

+ 代码重构

+ 修改签到策略

大大提高一次运行，贴吧签到的成功率，基本很少的贴吧会签到失败。

+ 去除多用户的支持

+ 增加支持server酱推送，可以推送至微信

## 2020-10-19

~~增加支持多账户签到，每个账号的`BDUSS`使用`&&`分割，具体格式如下。~~
