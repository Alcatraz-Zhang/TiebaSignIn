#!/usr/bin/env python3
"""百度贴吧签到 - 单元测试 (全部使用 mock，不发真实网络请求)。

运行: python -m pytest test_tieba.py -v   或   python test_tieba.py
"""

import unittest
from unittest import mock

from tieba_client import TiebaClient, best_status, STATUS_PRIORITY
import run


# ---------- 一个最小的假 client，给 run_signin 用 ----------
class FakeClient:
    """按预设脚本返回签到结果的假客户端。

    client_script[name] = [status, status, ...]  逐次调用 sign_forum 弹出
    web_script[name]    = [status, ...]          逐次调用 sign_forum_web 弹出
    """

    def __init__(self, client_script, web_script=None, logged_in=True):
        self.client_script = {k: list(v) for k, v in client_script.items()}
        self.web_script = {k: list(v) for k, v in (web_script or {}).items()}
        self.logged_in = logged_in
        self.stoken = "fake_stoken" if web_script else None
        self.tbs_calls = 0

    def get_tbs(self):
        self.tbs_calls += 1
        return "faketbs"

    def _next_status(self, script, name):
        seq = script.get(name, ["fatal"])
        return seq.pop(0) if seq else "fatal"

    def sign_forum(self, fid, name, tbs):
        status = self._next_status(self.client_script, name)
        return {"status": status, "rank": 1 if status == "success" else None,
                "message": status}

    def sign_forum_web(self, kw, tbs):
        status = self._next_status(self.web_script, kw)
        return {"status": status, "rank": None, "message": "web:" + status}


def _forum(name, fid="1"):
    return {"id": fid, "name": name}


class TestCategorization(unittest.TestCase):
    """客户端 / web 端 error_code -> status 分类映射。"""

    def _client(self):
        c = TiebaClient("bduss_x")
        return c

    def test_client_success(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "0",
                               "user_info": {"user_sign_rank": "7"}}):
            r = c.sign_forum("1", "战狼", "tbs")
        self.assertEqual(r["status"], "success")
        self.assertEqual(r["rank"], 7)

    def test_client_already(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "160002"}):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "already")

    def test_client_retryable_1102(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "1102"}):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "retryable")

    def test_client_retryable_1989(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "1989"}):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "retryable")

    def test_client_fatal_340006(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "340006"}):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "fatal")

    def test_client_unknown_is_fatal(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value={"error_code": "999999"}):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "fatal")

    def test_client_no_response_is_retryable(self):
        c = self._client()
        with mock.patch.object(c, "_request", return_value=None):
            self.assertEqual(c.sign_forum("1", "a", "t")["status"], "retryable")

    def test_web_success(self):
        c = TiebaClient("b", stoken="s")
        with mock.patch.object(c, "_request", return_value={"no": 0}):
            self.assertEqual(c.sign_forum_web("kw", "t")["status"], "success")

    def test_web_already_1101(self):
        c = TiebaClient("b", stoken="s")
        with mock.patch.object(c, "_request", return_value={"no": 1101}):
            self.assertEqual(c.sign_forum_web("kw", "t")["status"], "already")

    def test_web_retryable_2280007(self):
        c = TiebaClient("b", stoken="s")
        with mock.patch.object(c, "_request", return_value={"no": 2280007}):
            self.assertEqual(c.sign_forum_web("kw", "t")["status"], "retryable")

    def test_web_unknown_is_fatal(self):
        c = TiebaClient("b", stoken="s")
        with mock.patch.object(c, "_request", return_value={"no": 55555}):
            self.assertEqual(c.sign_forum_web("kw", "t")["status"], "fatal")


class TestBestStatus(unittest.TestCase):
    def test_priority_order(self):
        self.assertLess(STATUS_PRIORITY["success"], STATUS_PRIORITY["already"])
        self.assertLess(STATUS_PRIORITY["already"], STATUS_PRIORITY["retryable"])
        self.assertLess(STATUS_PRIORITY["retryable"], STATUS_PRIORITY["fatal"])

    def test_best_picks_better(self):
        self.assertEqual(best_status("fatal", "success"), "success")
        self.assertEqual(best_status("retryable", "already"), "already")
        self.assertEqual(best_status("fatal", "retryable"), "retryable")


class TestStokenInjection(unittest.TestCase):
    def test_stoken_added_to_cookies(self):
        c = TiebaClient("mybduss", stoken="mystoken")
        jar = c.session.cookies.get_dict()
        self.assertEqual(jar.get("BDUSS"), "mybduss")
        self.assertEqual(jar.get("STOKEN"), "mystoken")

    def test_no_stoken_ok(self):
        c = TiebaClient("mybduss")
        jar = c.session.cookies.get_dict()
        self.assertEqual(jar.get("BDUSS"), "mybduss")
        self.assertNotIn("STOKEN", jar)


class TestGetTbsSetsLogin(unittest.TestCase):
    def test_logged_in_true(self):
        c = TiebaClient("b")
        with mock.patch.object(c, "_request",
                               return_value={"is_login": "1", "tbs": "abc"}):
            tbs = c.get_tbs()
        self.assertEqual(tbs, "abc")
        self.assertTrue(c.logged_in)

    def test_logged_in_false(self):
        c = TiebaClient("b")
        with mock.patch.object(c, "_request",
                               return_value={"is_login": "0"}):
            c.get_tbs()
        self.assertFalse(c.logged_in)


class TestRetryLoop(unittest.TestCase):
    """run.run_signin 的多轮重试行为。"""

    def test_retryable_then_success(self):
        # 第一轮 retryable, 第二轮 success
        fc = FakeClient({"贴吧A": ["retryable", "success"]})
        stats = run.run_signin(fc, "tbs", [_forum("贴吧A")],
                               max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧A", stats["success"])
        self.assertNotIn("贴吧A", stats["failed"])
        self.assertGreaterEqual(fc.tbs_calls, 1)  # 轮间重新取 tbs

    def test_persistent_retryable_ends_failed(self):
        # 一直 retryable, 耗尽 5 轮后算 failed
        fc = FakeClient({"贴吧B": ["retryable"] * 10})
        stats = run.run_signin(fc, "tbs", [_forum("贴吧B")],
                               max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧B", stats["failed"])
        self.assertNotIn("贴吧B", stats["success"])

    def test_already_signed_not_failed(self):
        fc = FakeClient({"贴吧C": ["already"]})
        stats = run.run_signin(fc, "tbs", [_forum("贴吧C")],
                               max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧C", stats["already"])
        self.assertNotIn("贴吧C", stats["failed"])

    def test_fatal_no_retry(self):
        # fatal 第一轮就定论, 不应再调用第二次 sign_forum
        fc = FakeClient({"贴吧D": ["fatal", "success"]})
        stats = run.run_signin(fc, "tbs", [_forum("贴吧D")],
                               max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧D", stats["failed"])
        self.assertNotIn("贴吧D", stats["success"])

    def test_web_fallback_rescues_client_failure(self):
        # 客户端 fatal, web 端 success -> 取最优 success
        fc = FakeClient({"贴吧E": ["fatal"]},
                        web_script={"贴吧E": ["success"]})
        stats = run.run_signin(fc, "tbs", [_forum("贴吧E")],
                               max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧E", stats["success"])

    def test_no_web_fallback_when_no_stoken(self):
        # 没有 stoken 时不调用 web 兜底; 客户端 fatal -> failed
        fc = FakeClient({"贴吧F": ["fatal"]})  # web_script None -> stoken None
        with mock.patch.object(fc, "sign_forum_web",
                               side_effect=AssertionError("不应调用 web")):
            stats = run.run_signin(fc, "tbs", [_forum("贴吧F")],
                                   max_rounds=5, sleeper=lambda *_: None)
        self.assertIn("贴吧F", stats["failed"])


class TestPushPlus(unittest.TestCase):
    def test_send_pushplus_calls_endpoint(self):
        fake_http = mock.MagicMock()
        fake_resp = mock.MagicMock()
        fake_resp.text = '{"code":200}'
        fake_http.get.return_value = fake_resp
        ok = run.send_pushplus("mytoken", "内容正文", logged_in=True, http=fake_http)
        self.assertTrue(ok)
        self.assertTrue(fake_http.get.called)
        url = fake_http.get.call_args[0][0]
        params = fake_http.get.call_args[1].get("params", {})
        self.assertIn("pushplus.plus/send", url)
        self.assertEqual(params.get("token"), "mytoken")

    def test_send_pushplus_empty_token_skips(self):
        fake_http = mock.MagicMock()
        ok = run.send_pushplus("", "x", logged_in=True, http=fake_http)
        self.assertFalse(ok)
        self.assertFalse(fake_http.get.called)


if __name__ == "__main__":
    unittest.main(verbosity=2)
