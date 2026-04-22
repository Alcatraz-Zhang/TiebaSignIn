package top.srcrs;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 程序运行开始的地方
 *
 * @author srcrs
 * @Time 2020-10-31
 */
public class Run {
    /**
     * 获取日志记录器对象
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /**
     * 获取用户所有关注贴吧（移动端 API，最多 200）
     */
    String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    /**
     * 获取用户所有关注贴吧（PC 我的关注页，支持分页，可突破 200 上限）
     */
    String MYLIKE_URL = "https://tieba.baidu.com/f/like/mylike";
    /**
     * 获取用户的tbs
     */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /**
     * 贴吧签到接口（客户端）
     */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";
    /**
     * 贴吧签到接口（web 端，需要 STOKEN）
     */
    String SIGN_WEB_URL = "https://tieba.baidu.com/sign/add";

    /**
     * 存储用户所关注的待签到贴吧
     */
    private List<String> follow = new ArrayList<>();
    /**
     * 签到成功的贴吧列表
     */
    private static List<String> success = new ArrayList<>();

    /**
     * 签到失败的贴吧列表
     */
    private static HashSet<String> failed = new HashSet<String>();

    /**
     * 失效的贴吧列表
     */
    private static List<String> invalid = new ArrayList<>();

    /**
     * 用户的tbs
     */
    private String tbs = "";
    /**
     * 是否已成功登录（获取 tbs 时确认）
     */
    private boolean loggedIn = false;
    /**
     * 用户所关注的贴吧总数
     */
    private static Integer followNum = 0;

    public static void main(String[] args) {
        Cookie cookie = Cookie.getInstance();

        // 优先读取环境变量，缺失时回退到位置参数（向后兼容本地调试方式）
        String bduss = System.getenv("BDUSS");
        String stoken = System.getenv("STOKEN");
        String sckey = System.getenv("SCKEY");

        if ((bduss == null || bduss.isEmpty()) && args.length > 0) {
            bduss = args[0];
        }
        if ((sckey == null || sckey.isEmpty()) && args.length > 1) {
            sckey = args[1];
        }

        if (bduss == null || bduss.isEmpty()) {
            LOGGER.warn("请在 Secrets 中填写 BDUSS");
            return;
        }
        cookie.setBDUSS(bduss);

        if (stoken == null || stoken.isEmpty()) {
            LOGGER.warn("STOKEN 未设置，web 端签到接口可能因百度鉴权升级而失败，建议在 Secrets 中添加 STOKEN");
        } else {
            cookie.setStoken(stoken);
        }

        Run run = new Run();
        run.getTbs();
        run.getFollow();
        run.runSign();
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {} - {} ", followNum, success.size(), followNum - success.size(), failed);
        LOGGER.info("失效 {} 个贴吧: {} ", invalid.size(), invalid);

        // 打印 Cookie 字段（不打印值），便于排查鉴权问题
        String cookieFields = "BDUSS=已设置" + (cookie.getStoken() != null && !cookie.getStoken().isEmpty()
                ? ", STOKEN=已设置" : "（未设置 STOKEN，建议添加）");
        LOGGER.info("本次使用的 Cookie 字段: {}", cookieFields);

        if (sckey != null && !sckey.isEmpty()) {
            run.send(sckey, run.loggedIn);
        }
    }

    /**
     * 进行登录，获得 tbs，签到时需要用到这个参数。
     * 失败时重试一次（3 秒后），减少偶发网络抖动导致整次任务失败。
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getTbs() {
        int maxRetry = 2;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                JSONObject jsonObject = Request.get(TBS_URL);
                if (jsonObject != null && "1".equals(jsonObject.getString("is_login"))) {
                    LOGGER.info("获取tbs成功");
                    loggedIn = true;
                    tbs = jsonObject.getString("tbs");
                    return;
                } else {
                    LOGGER.warn("获取tbs失败，未登录（请检查 BDUSS/STOKEN 是否有效）-- {}", jsonObject);
                    return;  // 未登录不需要重试
                }
            } catch (Exception e) {
                LOGGER.error("获取tbs出现错误（第 {}/{} 次）-- {}", attempt, maxRetry, e.getMessage());
                if (attempt < maxRetry) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    /**
     * 获取用户所关注的贴吧列表。
     * 优先走我的关注 HTML 页（可突破 200 上限），失败时回退到移动端 API。
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow() {
        if (!getFollowFromMyLike()) {
            getFollowFromApi();
        }
    }

    /**
     * 通过分页抓取 PC 端"我的关注"页面获取贴吧列表，可突破移动端 API 的 200 上限。
     *
     * @return 成功获取到至少一个贴吧时返回 true，否则返回 false
     */
    private boolean getFollowFromMyLike() {
        try {
            List<String> allForums = new ArrayList<>();
            Pattern kwPattern = Pattern.compile("href=\"/f\\?kw=([^\"&]+)");
            int pn = 1;
            while (true) {
                String html = Request.getHtml(MYLIKE_URL + "?&pn=" + pn);
                if (html == null || html.isEmpty()) break;
                List<String> pageForums = new ArrayList<>();
                Matcher m = kwPattern.matcher(html);
                while (m.find()) {
                    String kw = URLDecoder.decode(m.group(1), "UTF-8");
                    if (!allForums.contains(kw) && !pageForums.contains(kw)) {
                        pageForums.add(kw);
                    }
                }
                if (pageForums.isEmpty()) break;
                allForums.addAll(pageForums);
                pn++;
            }
            if (allForums.isEmpty()) return false;

            for (String name : allForums) {
                follow.add(name.replace("+", "%2B"));
            }
            followNum = allForums.size();
            LOGGER.info("从我的关注页获取到 {} 个贴吧（共 {} 页）", followNum, pn - 1);
            return true;
        } catch (Exception e) {
            LOGGER.warn("从我的关注页获取贴吧列表失败，将回退到移动端 API -- {}", e.getMessage());
            return false;
        }
    }

    /**
     * 通过移动端 API 获取贴吧列表（最多 200 个，原有逻辑）。
     */
    private void getFollowFromApi() {
        try {
            JSONObject jsonObject = Request.get(LIKE_URL);
            JSONObject data = jsonObject != null ? jsonObject.getJSONObject("data") : null;
            JSONArray jsonArray = data != null ? data.getJSONArray("like_forum") : null;
            if (jsonArray == null) {
                String preview = jsonObject != null
                        ? jsonObject.toJSONString().substring(0, Math.min(200, jsonObject.toJSONString().length()))
                        : "null";
                LOGGER.error("获取贴吧列表失败，接口返回: {}", preview);
                return;
            }
            LOGGER.info("获取贴吧列表成功，共 {} 个", jsonArray.size());
            followNum = jsonArray.size();
            // 获取用户所有关注的贴吧
            for (Object array : jsonArray) {
                String tiebaName = ((JSONObject) array).getString("forum_name");
                if ("0".equals(((JSONObject) array).getString("is_sign"))) {
                    // 将未签到的贴吧加入到 follow 中，待签到
                    follow.add(tiebaName.replace("+", "%2B"));
                    // 过滤失效的贴吧
                    if (Request.isTiebaNotExist(tiebaName)) {
                        follow.remove(tiebaName.replace("+", "%2B"));
                        invalid.add(tiebaName);
                        failed.add(tiebaName);
                    }
                } else {
                    // 将已经成功签到的贴吧，加入到 success
                    success.add(tiebaName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("获取贴吧列表部分出现错误 -- {}", e);
        }
    }

    /**
     * 使用 web 端接口签到（需要 STOKEN）。
     *
     * @param kw 贴吧名（已解码，无 %2B）
     * @return 签到成功返回 true
     */
    private boolean signWeb(String kw) {
        try {
            String encodedKw = URLEncoder.encode(kw, "UTF-8");
            String body = "ie=utf-8&kw=" + encodedKw + "&tbs=" + tbs;
            String referer = "https://tieba.baidu.com/f?kw=" + encodedKw + "&ie=utf-8";
            JSONObject result = Request.post(SIGN_WEB_URL, body, referer);
            if (result == null) return false;
            int no = result.getIntValue("no");
            if (no == 0) {
                LOGGER.info("{}: 签到成功 (web)", kw);
                return true;
            }
            LOGGER.debug("{}: web签到失败, no={}, error={}", kw, no, result.getString("error"));
            return false;
        } catch (Exception e) {
            LOGGER.debug("{}: signWeb 异常 -- {}", kw, e.getMessage());
            return false;
        }
    }

    /**
     * 使用客户端接口签到（原有方式，作为 web 端失败时的 fallback）。
     *
     * @param s 贴吧名（+号已编码为 %2B）
     * @return 签到成功返回 true
     */
    private boolean signClient(String s) {
        try {
            String rotation = s.replace("%2B", "+");
            String body = "kw=" + s + "&tbs=" + tbs + "&sign=" + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
            JSONObject post = Request.post(SIGN_URL, body);
            if (post == null) return false;
            if ("0".equals(post.getString("error_code"))) {
                LOGGER.info("{}: 签到成功 (client)", rotation);
                return true;
            }
            LOGGER.debug("{}: client签到失败, error_code={}", rotation, post.getString("error_code"));
            return false;
        } catch (Exception e) {
            LOGGER.debug("{}: signClient 异常 -- {}", s, e.getMessage());
            return false;
        }
    }

    /**
     * 开始进行签到，每一轮将所有未签到的贴吧进行签到，一共进行 5 轮，全部签到完提前结束。
     * 优先走 web 端接口，失败时回退到客户端接口。
     * 若未登录则直接退出，不再空转。
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign() {
        if (!loggedIn) {
            LOGGER.warn("未登录，跳过签到（请检查 BDUSS/STOKEN 是否有效）");
            return;
        }
        Integer flag = 5;
        try {
            while (!follow.isEmpty() && flag > 0) {
                LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                LOGGER.info("还剩 {} 个贴吧需要签到", follow.size());
                Iterator<String> iterator = follow.iterator();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String rotation = s.replace("%2B", "+");
                    int randomTime = new Random().nextInt(200) + 300;
                    LOGGER.info("等待 {} 毫秒", randomTime);
                    TimeUnit.MILLISECONDS.sleep(randomTime);
                    boolean signed = signWeb(rotation);
                    if (!signed) {
                        signed = signClient(s);
                    }
                    if (signed) {
                        iterator.remove();
                        success.add(rotation);
                        failed.remove(rotation);
                    } else {
                        failed.add(rotation);
                        LOGGER.warn("{}: 签到失败", rotation);
                    }
                }
                if (!follow.isEmpty()) {
                    // 仍有未签到的，等待一段时间后重试
                    int waitSecs = 60 + new Random().nextInt(31);
                    LOGGER.info("本轮结束，等待 {} 秒后重试（还剩 {} 个）", waitSecs, follow.size());
                    Thread.sleep(1000L * waitSecs);
                    getTbs();
                }
                flag--;
            }
        } catch (Exception e) {
            LOGGER.error("签到部分出现错误 -- {}", e);
        }
    }

    /**
     * 发送运行结果到微信，通过 server 酱
     *
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
    /**   public void send(String sckey) {
       
        String text = "总: " + followNum + " - ";
        text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String desp = "共 " + followNum + " 贴吧\n\n";
        desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String body = "text=" + text + "&desp=" + "TiebaSignIn运行结果\n\n" + desp;
        StringEntity entityBody = new StringEntity(body, "UTF-8");
        HttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://sc.ftqq.com/" + sckey + ".send");
        httpPost.addHeader("Content-Type", "application/x-www-form-urlencoded");
        httpPost.setEntity(entityBody);
        HttpResponse resp = null;
        String respContent = null;
        try {
            resp = client.execute(httpPost);
            HttpEntity entity = null;
            if (resp.getStatusLine().getStatusCode() < 400) {
                entity = resp.getEntity();
            } else {
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
            LOGGER.info("server酱推送正常");
        } catch (Exception e) {
            LOGGER.error("server酱发送失败 -- " + e);
        }
    } 
**/
      /**
     * 发送运行结果到微信，通过 PUSHPLUS
     *
     * @param sckey     PushPlus token
     * @param loggedIn  是否已登录（展示在推送内容中方便用户判断账号状态）
     * @author srcrs
     * @Time 2020-10-31
     */
     public void send(String sckey, boolean loggedIn) {
        /** 将要推送的数据 */
        String loginStatus = loggedIn ? "已登录" : "未登录（请检查 BDUSS/STOKEN）";
        String text = "总: " + followNum + " - ";
        text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
        String desp = "登录状态: " + loginStatus + "\n\n";
        desp += "共 " + followNum + " 贴吧\n";
        desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());

try {
            String token = sckey;
            String title = URLEncoder.encode("百度贴吧自动签到", "UTF-8");
            String content = URLEncoder.encode(desp, "UTF-8");
            String urlx = "https://www.pushplus.plus/send?title=" + title + "&content=" + content + "&token=" + token;
            URL url = new URL(urlx);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            StringBuilder response = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            System.out.println("Response: " + response.toString());
            connection.disconnect();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
