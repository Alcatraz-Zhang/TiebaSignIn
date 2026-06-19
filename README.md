<div align="center"> 
<h1 align="center">贴吧签到助手</h1>
<img src="https://img.shields.io/github/issues/Alcatraz-Zhang/TiebaSignIn?color=green">
<img src="https://img.shields.io/github/stars/Alcatraz-Zhang/TiebaSignIn?color=yellow">
<img src="https://img.shields.io/github/forks/Alcatraz-Zhang/TiebaSignIn?color=orange">
<img src="https://img.shields.io/github/license/Alcatraz-Zhang/TiebaSignIn?color=ff69b4">
</div>

## 简介

每日自动签到百度贴吧所有关注的贴吧，支持超过 200 个。使用 GitHub Actions 定时运行，无需自备服务器。

## 功能

- **每日自动签到**：北京时间每天早上 6:30 自动执行。
- **突破 200 个贴吧限制**：自动翻页获取完整关注列表。
- **客户端接口优先**：使用手机端接口签到，经验值更高。
- **Web 端兜底**：客户端接口失败时，自动回退到 web 端接口重试。
- **多轮重试**：最多 5 轮重试，间隔 60–90 秒，自动处理临时性失败。
- **结果分类统计**：成功、已签到、失效、失败分开统计，不浪费重试次数。
- **微信推送**：支持通过 PushPlus 推送签到结果（可选）。
- **自动保活**：使用 `liskin/gh-workflow-keepalive@v1` 保持 GitHub Actions 定时任务长期可用，不产生虚假提交。

## 使用方法

### 1. Fork 本项目

点击右上角 **Fork** 按钮，将仓库复制到自己的 GitHub 账号下。

### 2. 启用 Actions

进入 Fork 后的仓库：`Settings -> Actions -> General`

- 确保 **Allow all actions and reusable workflows** 已勾选。
- 保存即可，`workflow-keepalive` job 已自带 `actions: write` 权限，无需授予仓库 `Read and write permissions`。

同时检查仓库没有被 Archived：`Settings -> General -> Danger Zone`。

### 3. 获取 BDUSS 和 STOKEN

1. 在浏览器中登录 [百度贴吧](https://tieba.baidu.com/)。
2. 按 `F12` 打开开发者工具。
3. 切换到 `Application`（或 `Storage`）-> `Cookies -> https://tieba.baidu.com`。
4. 分别复制 `BDUSS` 和 `STOKEN` 对应的 `Value`。

> **⚠️ 注意**：`STOKEN` 可能有多个，**请选择域为 `tieba.baidu.com` 的那个**。`passport.baidu.com` 域下的 STOKEN 对贴吧接口无效。
>
> 由于百度接口鉴权升级，**仅填 BDUSS 会导致签到失败（返回未登录）**，STOKEN 现在是必填项。

### 4. 添加 Secrets

进入 `Settings -> Secrets and variables -> Actions -> New repository secret`，添加：

| Name  | 必填 | 说明 |
| ----- | ---- | ---- |
| BDUSS | 是   | 贴吧登录凭证 |
| STOKEN | 是   | 贴吧登录凭证，需与 BDUSS 同时使用 |
| SCKEY | 否   | PushPlus token，用于微信推送签到结果 |

### 5. 首次触发 Actions

GitHub 默认会暂停 Fork 仓库的 Actions 调度，需要一次真实的 `push` 事件来激活：

1. 打开仓库根目录的 `run.txt`。
2. 将 `flag` 的值从 `0` 改为 `1`：

```patch
- flag: 0
+ flag: 1
```

3. 提交该修改，Actions 将开始正常运行。

### 6. 完成

每天早上 **北京时间 6:30** 会自动签到。可在 `Actions` 页面查看运行日志。

## 可选：开启 PushPlus 微信推送

在 Secrets 中添加 `SCKEY` 后，每次签到结束会自动推送结果到微信。

- 注册/获取 token：[PushPlus 官网](http://www.pushplus.plus/)
- 将获取到的 token 填入 Secrets 的 `SCKEY` 中

## 工作原理

### 签到流程

`tieba.yml` 每天定时运行：

1. 检出代码并安装 `requests`。
2. 读取 `BDUSS`、`STOKEN`、`SCKEY`。
3. 验证登录状态并获取关注贴吧列表。
4. 逐个使用客户端接口签到；失败时自动使用 web 端接口兜底。
5. 对需要重试的状态进入下一轮，最多 5 轮。
6. 汇总结果，若配置了 `SCKEY` 则推送至微信。

### 自动保活

GitHub 会在仓库 60 天无活动时自动禁用 schedule workflow。`tieba.yml` 中的 `workflow-keepalive` job 使用 [liskin/gh-workflow-keepalive](https://github.com/liskin/gh-workflow-keepalive) 在每次定时运行时通过 GitHub API 重新启用工作流，**不产生任何虚假提交**。

## 常见问题

**Q: 日志提示「未登录」怎么办？**

A: 检查 Secrets 中的 `BDUSS` 和 `STOKEN` 是否正确，且 `STOKEN` 的域是 `tieba.baidu.com`。修改百度密码或退出登录后需要重新获取。

**Q: 为什么有些贴吧签到失败？**

A: 常见原因包括：贴吧被封禁、账号被风控、接口临时异常。多轮重试会自动处理部分临时失败，最终在日志汇总中可以看到失败贴吧名称。

**Q: 可以关闭微信推送吗？**

A: 不填 `SCKEY` 即可，代码会自动跳过推送。

## 免责声明

本项目仅供学习交流使用，请遵守百度贴吧相关服务条款。因使用本项目导致的账号问题，开发者不承担责任。
