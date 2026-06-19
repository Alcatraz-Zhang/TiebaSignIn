#!/usr/bin/env python3
"""百度贴吧自动签到 - GitHub Actions 入口

用法:
    python run.py                          # 自动读取环境变量 BDUSS / STOKEN / SCKEY
    python run.py --bduss "your_bduss"     # 命令行传入 BDUSS
    python run.py --bduss "x" --stoken "y" # 同时传入 STOKEN（web 兜底）
"""

import argparse
import logging
import os
import random
import time
import urllib.parse
from typing import Callable, List, Optional

import requests
from tieba_client import TiebaClient, best_result

logging.basicConfig(
    level=logging.INFO,
    format="[%(levelname)s] %(asctime)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


def default_sleeper(event: str, idx: int, total: int) -> None:
    """默认节流/等待策略。

    event:
        "step"  - 单个贴吧签到后的间隔
        "round" - 重试轮次之间的间隔
    """
    if event == "step":
        if (idx + 1) % 10 == 0:
            extra = random.uniform(5, 10)
            logger.info(f"已签到 {idx + 1}/{total} 个，休息 {extra:.1f}s ...")
            time.sleep(extra)
        else:
            time.sleep(random.uniform(1.0, 2.5))
    elif event == "round":
        wait = random.uniform(60, 90)
        logger.info(f"第 {idx + 1} 轮重试结束，等待 {wait:.1f}s 后继续...")
        time.sleep(wait)


def parse_args() -> tuple[str, str]:
    parser = argparse.ArgumentParser(description="百度贴吧自动签到")
    parser.add_argument(
        "--bduss",
        default=None,
        help="贴吧 BDUSS Cookie 值（优先级高于环境变量）",
    )
    parser.add_argument(
        "--stoken",
        default=None,
        help="贴吧 STOKEN Cookie 值（优先级高于环境变量，用于 web 端兜底）",
    )
    args = parser.parse_args()

    bduss = args.bduss or os.environ.get("BDUSS", "")
    stoken = args.stoken or os.environ.get("STOKEN", "")
    if not bduss:
        parser.error("请通过 --bduss 参数或 BDUSS 环境变量提供 BDUSS")
    return bduss, stoken


def run_signin(
    client: TiebaClient,
    tbs: str,
    forums: List[dict],
    max_rounds: int = 5,
    sleeper: Optional[Callable[[str, int, int], None]] = None,
) -> dict:
    """多轮签到主循环。

    返回 stats:
        {
            "success": [成功贴吧名, ...],
            "already": [已签到贴吧名, ...],
            "failed":  [失败贴吧名, ...],
        }
    """
    if sleeper is None:
        sleeper = default_sleeper

    queue = list(forums)
    stats = {"success": [], "already": [], "failed": []}

    for round_idx in range(max_rounds):
        if round_idx > 0:
            # 每轮重试前刷新 tbs（避免旧 tbs 导致 1989 等验证失败）
            new_tbs = client.get_tbs()
            if new_tbs is None:
                logger.error("轮间刷新 tbs 失败，剩余贴吧全部标记失败")
                stats["failed"].extend(f.get("name", "") for f in queue)
                break
            tbs = new_tbs
            sleeper("round", round_idx, len(queue))

        next_queue: List[dict] = []
        for idx, forum in enumerate(queue):
            # 同一轮内第一个贴吧不额外等待，后续贴吧按策略节流
            if round_idx > 0 or idx > 0:
                sleeper("step", idx, len(queue))

            name = forum.get("name", "")
            fid = forum.get("id", "")
            result = client.sign_forum(fid, name, tbs)

            if result["status"] in ("retryable", "fatal") and client.stoken:
                web_result = client.sign_forum_web(name, tbs)
                result = best_result(result, web_result)

            if result["status"] == "retryable":
                next_queue.append(forum)
            elif result["status"] == "success":
                rank_str = f"，第 {result['rank']} 个签到" if result.get("rank") else ""
                logger.info(f"【{name}】签到成功{rank_str}")
                stats["success"].append(name)
            elif result["status"] == "already":
                logger.info(f"【{name}】{result['message']}")
                stats["already"].append(name)
            else:  # fatal
                logger.error(f"【{name}】签到失败: {result['message']}")
                stats["failed"].append(name)

        queue = next_queue
        if not queue:
            break

    # 超出最大轮次仍未成功的，全部计为失败
    stats["failed"].extend(f.get("name", "") for f in queue)
    return stats


def send_pushplus(
    token: str,
    content: str,
    logged_in: bool = True,
    http: Optional[requests.Session] = None,
) -> bool:
    """通过 PushPlus 推送签到结果。

    Args:
        token: PushPlus token（即 README 中的 SCKEY）
        content: 推送正文
        logged_in: BDUSS 是否校验通过；False 时会在正文前追加未登录提示
        http: 可选的 requests-like 对象，用于测试 mock
    """
    if not token:
        return False

    if not logged_in:
        content = "【未登录】本次签到未能开始，请检查 BDUSS 是否有效。\n" + content

    try:
        payload = {
            "token": token,
            "title": "贴吧签到结果",
            "content": content,
            "template": "html",
        }
        url = "http://www.pushplus.plus/send?" + urllib.parse.urlencode(payload)
        if http is None:
            r = requests.get(url, timeout=10)
        else:
            r = http.get(url, timeout=10)
        logger.info(f"PushPlus 响应: {r.text[:100]}")
        return True
    except Exception as e:
        logger.error(f"PushPlus 推送失败: {e}")
        return False


def build_summary(stats: dict, total: int) -> str:
    success = len(stats["success"])
    already = len(stats["already"])
    failed_count = len(stats["failed"])
    failed_names = stats["failed"]

    lines = [
        "========== 签到汇总 ==========",
        f"贴吧总数: {total}",
        f"签到成功: {success}",
        f"已经签到: {already}",
        f"签到失败: {failed_count}",
        "================================",
    ]

    if failed_names:
        lines.append("失败贴吧:")
        lines.extend(f"  - {n}" for n in failed_names[:50])
        if len(failed_names) > 50:
            lines.append(f"  ... 还有 {len(failed_names) - 50} 个")

    return "\n".join(lines)


def main() -> None:
    bduss, stoken = parse_args()
    sckey = os.environ.get("SCKEY", "")
    client = TiebaClient(bduss, stoken=stoken)

    # 1. 获取 tbs 并校验登录状态
    logger.info("正在获取 tbs...")
    tbs = client.get_tbs()
    if not tbs:
        logger.error("获取 tbs 失败，退出")
        summary = "tbs 获取失败，本次签到未能开始。"
        send_pushplus(sckey, summary, logged_in=client.logged_in)
        raise SystemExit(1)

    # 2. 获取关注的贴吧列表
    logger.info("正在获取关注的贴吧列表...")
    forums = client.get_favorites()
    if not forums:
        logger.warning("未获取到关注的贴吧，签到结束")
        summary = "未获取到关注的贴吧。"
        send_pushplus(sckey, summary, logged_in=client.logged_in)
        return

    # 3. 多轮签到
    total = len(forums)
    logger.info(f"开始签到 {total} 个贴吧")
    stats = run_signin(client, tbs, forums)

    # 4. 汇总 + 推送
    summary = build_summary(stats, total)
    logger.info("\n" + summary)
    send_pushplus(sckey, summary, logged_in=client.logged_in)


if __name__ == "__main__":
    main()
