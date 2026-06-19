import hashlib
import logging
import random
import time
from typing import Optional

import requests

logger = logging.getLogger(__name__)

# ---------- constants ----------
SIGN_KEY = "tiebaclient!!!"
TBS_URL = "http://tieba.baidu.com/dc/common/tbs"
LIKE_URL = "http://c.tieba.baidu.com/c/f/forum/like"
SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign"
WEB_SIGN_URL = "https://tieba.baidu.com/sign/add"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/95.0.4638.69 Safari/537.36"
    ),
}

BASE_SIGN_DATA = {
    "_client_type": "2",
    "_client_version": "9.7.8.0",
    "_phone_imei": "000000000000000",
    "model": "MI+5",
    "net_type": "1",
}

# ---------- status helpers ----------
# 状态优先级：越小越好（success < already < retryable < fatal）
STATUS_PRIORITY = {
    "success": 0,
    "already": 1,
    "retryable": 2,
    "fatal": 3,
}

# 客户端 API 错误码分类
CLIENT_RETRYABLE_CODES = {"1102", "1989", "2150040"}
CLIENT_ALREADY_CODE = "160002"

# web 端 API 错误码分类
WEB_RETRYABLE_CODES = {2280007, 3150005}
WEB_ALREADY_CODE = 1101


def best_status(a: str, b: str) -> str:
    """返回两个状态中的较优者。"""
    return a if STATUS_PRIORITY[a] <= STATUS_PRIORITY[b] else b


def best_result(a: dict, b: dict) -> dict:
    """返回两个结果中状态较优的完整 dict。"""
    return a if best_status(a["status"], b["status"]) == a["status"] else b


# ---------- client ----------
class TiebaClient:
    """
    百度贴吧签到客户端
    """

    def __init__(self, bduss: str, stoken: Optional[str] = None) -> None:
        if not bduss:
            raise ValueError("BDUSS 不能为空")
        self.bduss = bduss
        self.stoken = stoken
        self.logged_in = False
        self._session: Optional[requests.Session] = None

    # -- session 惰性初始化 --
    @property
    def session(self) -> requests.Session:
        if self._session is None:
            self._session = requests.Session()
            self._session.headers.update(HEADERS)
            # 将 BDUSS 注入 Cookie，否则服务端收不到认证信息
            cookies = {"BDUSS": self.bduss}
            if self.stoken:
                cookies["STOKEN"] = self.stoken
            requests.utils.add_dict_to_cookiejar(
                self._session.cookies, cookies
            )
        return self._session

    # -- 签名算法 --
    @staticmethod
    def signature(data: dict) -> str:
        s = "".join(f"{k}={data[k]}" for k in sorted(data))
        return hashlib.md5((s + SIGN_KEY).encode()).hexdigest().upper()

    # -- 带指数退避的请求 --
    def _request(
        self,
        url: str,
        method: str = "get",
        data: Optional[dict] = None,
        headers: Optional[dict] = None,
        retry: int = 3,
    ) -> Optional[dict]:
        merged_headers = dict(HEADERS)
        if headers:
            merged_headers.update(headers)

        for i in range(retry):
            try:
                resp = self.session.request(
                    method.upper(), url, data=data, headers=merged_headers, timeout=10
                )
                resp.raise_for_status()
                if not resp.text.strip():
                    raise ValueError("空响应")
                return resp.json()
            except Exception as e:
                if i == retry - 1:
                    logger.error(f"请求失败(已重试 {retry} 次): {e}")
                    return None
                wait = 1.5 * (2**i) + random.uniform(0, 1)
                logger.warning(f"请求异常，{wait:.1f}s 后重试 ({i+1}/{retry}): {e}")
                time.sleep(wait)
        return None

    # -- 获取 tbs --
    def get_tbs(self) -> Optional[str]:
        """获取 tbs，并校验 BDUSS 登录状态。"""
        result = self._request(TBS_URL)
        if result is None:
            logger.error("获取 tbs 失败")
            return None
        self.logged_in = result.get("is_login") == "1"
        if not self.logged_in:
            logger.warning("BDUSS 登录状态异常: is_login=%s", result.get("is_login"))
        return result.get("tbs", "")

    # -- 获取关注的贴吧列表 --
    def get_favorites(self) -> list[dict]:
        forums: list[dict] = []
        page_no = 1

        while True:
            data = {
                **BASE_SIGN_DATA,
                "BDUSS": self.bduss,
                "_client_id": "wappc_1534235498291_488",
                "from": "1008621y",
                "page_no": str(page_no),
                "page_size": "200",
                "timestamp": str(int(time.time())),
                "vcode_tag": "11",
            }
            data["sign"] = self.signature(data)

            result = self._request(LIKE_URL, "post", data)
            if result is None:
                logger.warning("获取贴吧列表失败，停止翻页")
                break

            if "forum_list" in result:
                for forum_type in ("non-gconforum", "gconforum"):
                    items = result["forum_list"].get(forum_type, [])
                    if isinstance(items, list):
                        forums.extend(items)
                    elif isinstance(items, dict):
                        forums.append(items)

            if result.get("has_more") != "1":
                break

            page_no += 1
            time.sleep(random.uniform(1, 2))

        logger.info(f"共获取到 {len(forums)} 个关注的贴吧")
        return forums

    # -- 客户端单个贴吧签到 --
    def sign_forum(self, fid: str, name: str, tbs: str) -> dict:
        """
        返回状态:
            "success"   - 签到成功
            "already"   - 今日已签到
            "retryable" - 临时失败，可重试（如 1102 / 1989 / 2150040）
            "fatal"     - 不可重试失败（如 340006 / 2280007 / 540002 / 未知错误）
        """
        data = {**BASE_SIGN_DATA}
        data.update(
            {
                "BDUSS": self.bduss,
                "fid": fid,
                "kw": name,
                "tbs": tbs,
                "timestamp": str(int(time.time())),
            }
        )
        data["sign"] = self.signature(data)

        result = self._request(SIGN_URL, "post", data)
        if result is None:
            return {"status": "retryable", "rank": None, "message": "网络请求失败"}

        error_code = result.get("error_code", "")
        error_msg = result.get("error_msg", "")

        if error_code == "0":
            rank = None
            if "user_info" in result:
                rank = result["user_info"].get("user_sign_rank")
                rank = int(rank) if rank else None
            return {"status": "success", "rank": rank, "message": "签到成功"}
        elif error_code == CLIENT_ALREADY_CODE:
            return {"status": "already", "rank": None, "message": error_msg or "今日已签到"}
        elif error_code in CLIENT_RETRYABLE_CODES:
            return {"status": "retryable", "rank": None, "message": error_msg or f"客户端错误 {error_code}"}
        else:
            # 340006、2280007、540002 以及所有未知错误都视为 fatal
            return {"status": "fatal", "rank": None, "message": error_msg or f"客户端错误 {error_code}"}

    # -- web 端单个贴吧签到（兜底方案，需要 STOKEN） --
    def sign_forum_web(self, kw: str, tbs: str) -> dict:
        """
        web 端签到兜底。当客户端接口返回 retryable / 部分 fatal 时，
        使用 STOKEN Cookie 尝试 web 端 `sign/add`。
        """
        if not self.stoken:
            return {"status": "fatal", "rank": None, "message": "无 STOKEN，无法使用 web 兜底"}

        data = {
            "ie": "utf-8",
            "kw": kw,
            "tbs": tbs,
        }
        headers = {"Referer": f"https://tieba.baidu.com/f?kw={kw}&fr=home"}

        result = self._request(WEB_SIGN_URL, "post", data, headers=headers)
        if result is None:
            return {"status": "retryable", "rank": None, "message": "web 签到网络失败"}

        # web 端返回字段为 no / error
        no = result.get("no")
        try:
            no_int = int(no) if no is not None else -1
        except (TypeError, ValueError):
            no_int = -1
        error = result.get("error", "")

        if no_int == 0:
            return {"status": "success", "rank": None, "message": "web 签到成功"}
        elif no_int == WEB_ALREADY_CODE:
            return {"status": "already", "rank": None, "message": error or "web：今日已签到"}
        elif no_int in WEB_RETRYABLE_CODES:
            return {"status": "retryable", "rank": None, "message": error or f"web 需重试 {no_int}"}
        else:
            return {"status": "fatal", "rank": None, "message": error or f"web 错误 {no_int}"}
