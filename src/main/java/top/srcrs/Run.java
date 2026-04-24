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
 * 签到结果分类枚举
 */
enum SignResult {
    /** 本次首次签到成功（优先级最高） */
    SUCCESS(0),
    /** 今日已签到（非失败，不需要重试） */
    ALREADY_SIGNED(1),
    /** 暂时性失败，可重试（如签到过快、网络抖动、验证码等） */
    RETRYABLE(2),
    /** 永久性失败，不再重试（如贴吧不可用、风控封禁等） */
    FATAL(3);

    /** 数值越小优先级越高 */
    private final int priority;

    SignResult(int priority) {
        this.priority = priority;
    }

    /**
     * 返回两个结果中优先级更高（更好）的那个。
     */
    public static SignResult best(SignResult a, SignResult b) {
        return a.priority <= b.priority ? a : b;
    }
}

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
     * 贴吧签到接口（客户端，优先使用，经验值更高）
     */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";
    /**
     * 贴吧签到接口（web 端，需要 STOKEN，客户端失败时作为兜底）
     */
    String SIGN_WEB_URL = "https://tieba.baidu.com/sign/add";

    /**
     * 客户端接口 error_code → SignResult 映射表。
     * 未在表中的非零 code 视为 FATAL。
     */
    private static final Map<String, SignResult> CLIENT_CODE_MAP;
    static {
        Map<String, SignResult> m = new HashMap<>();
        m.put("0",       SignResult.SUCCESS);
        m.put("160002",  SignResult.ALREADY_SIGNED); // 您之前已经签过了
        m.put("1989",    SignResult.RETRYABLE);       // 需要验证
        m.put("2150040", SignResult.RETRYABLE);       // 风控/需要验证
        m.put("1102",    SignResult.RETRYABLE);       // 签到过快
        m.put("340006",  SignResult.FATAL);           // 贴吧目前不可用
        CLIENT_CODE_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * Web 接口 no → SignResult 映射表。
     * 未在表中的非零 no 视为 FATAL。
     */
    private static final Map<Integer, SignResult> WEB_CODE_MAP;
    static {
        Map<Integer, SignResult> m = new HashMap<>();
        m.put(0,       SignResult.SUCCESS);
        m.put(1101,    SignResult.ALREADY_SIGNED); // 已签到
        m.put(1102,    SignResult.RETRYABLE);       // 签到过快
        m.put(1989,    SignResult.RETRYABLE);       // 需要验证
        m.put(2280007, SignResult.RETRYABLE);       // 需要验证
        m.put(340006,  SignResult.FATAL);           // 贴吧目前不可用
        WEB_CODE_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * 存储用户所关注的待签到贴吧
     */
    private List<String> follow = new ArrayList<>();
    /**
     * 今日首次签到成功的贴吧列表
     */
    private static List<String> success = new ArrayList<>();
    /**
     * 今日已签到（非失败，无需重试）的贴吧列表
     */
    private static List<String> alreadySigned = new ArrayList<>();
    /**
     * 签到真正失败（无法完成签到）的贴吧集合
     */
    private static HashSet<String> failed = new HashSet<>();
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
        LOGGER.info("共 {} 个贴吧 — 新签到: {} — 已签到(跳过): {} — 失败: {} {} — 失效: {} {}",
                followNum, success.size(), alreadySigned.size(),
                failed.size(), failed,
                invalid.size(), invalid);

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
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("getTbs 重试等待被中断");
                    }
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
                    // 我的关注页（tieba.baidu.com/f/like/mylike）为 GBK 页面，
                    // href 中的 kw 是 GBK 百分号编码，必须用 GBK 解码，
                    // 否则中文贴吧名会变成乱码（如 "战狼女" -> "ս��Ů"），
                    // 进而导致后续签到接口返回 340006 "贴吧目录出问题啦"。
                    String kw = URLDecoder.decode(m.group(1), "GBK");
                    if (!allForums.contains(kw) && !pageForums.contains(kw)) {
                        pageForums.add(kw);
                    }
                }
                if (pageForums.isEmpty()) break;
                allForums.addAll(pageForums);
                pn++;
            }
            if (allForums.isEmpty()) return false;

            // 与 getFollowFromApi 保持一致：存储原始贴吧名（仅将 '+' 转义为 %2B，
            // 避免在 application/x-www-form-urlencoded 表单中被当作空格）。
            // 客户端签到接口的 MD5 sign 必须基于原始 kw 计算，web 端签到接口
            // 内部还会再次 URLEncoder.encode，因此这里不能预先做 URL 编码，
            // 否则中文贴吧名会出现签名不匹配 / 双重编码导致签到失败。
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
                String preview = "null";
                if (jsonObject != null) {
                    String json = jsonObject.toJSONString();
                    preview = json.substring(0, Math.min(200, json.length()));
                }
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
     * 使用客户端接口签到（优先使用，经验值更高）。
     * 返回分类后的签到结果。
     *
     * @param s 贴吧名（+号已编码为 %2B）
     * @return SignResult
     */
    private SignResult signClient(String s) {
        try {
            String rotation = s.replace("%2B", "+");
            String body = "kw=" + s + "&tbs=" + tbs + "&sign=" + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
            JSONObject post = Request.post(SIGN_URL, body);
            if (post == null) {
                LOGGER.debug("{}: client签到无响应，将重试", rotation);
                return SignResult.RETRYABLE;
            }
            String errorCode = post.getString("error_code");
            SignResult result = CLIENT_CODE_MAP.getOrDefault(errorCode, SignResult.FATAL);
            switch (result) {
                case SUCCESS:
                    LOGGER.info("{}: 签到成功 (client)", rotation);
                    break;
                case ALREADY_SIGNED:
                    LOGGER.info("{}: 今日已签到 (client)", rotation);
                    break;
                case RETRYABLE:
                    LOGGER.debug("{}: client签到暂时失败，将重试, error_code={}, msg={}", rotation, errorCode, post.getString("error_msg"));
                    break;
                default:
                    LOGGER.debug("{}: client签到失败, error_code={}, msg={}", rotation, errorCode, post.getString("error_msg"));
                    break;
            }
            return result;
        } catch (Exception e) {
            LOGGER.debug("{}: signClient 异常，将重试 -- {}", s, e.getMessage());
            return SignResult.RETRYABLE;
        }
    }

    /**
     * 使用 web 端接口签到（需要 STOKEN，客户端失败时的兜底）。
     * 返回分类后的签到结果。
     *
     * @param kw 贴吧名（已解码，无 %2B）
     * @return SignResult
     */
    private SignResult signWeb(String kw) {
        try {
            String encodedKw = URLEncoder.encode(kw, "UTF-8");
            String body = "ie=utf-8&kw=" + encodedKw + "&tbs=" + tbs;
            String referer = "https://tieba.baidu.com/f?kw=" + encodedKw + "&ie=utf-8";
            JSONObject result = Request.post(SIGN_WEB_URL, body, referer);
            if (result == null) {
                LOGGER.debug("{}: web签到无响应，将重试", kw);
                return SignResult.RETRYABLE;
            }
            int no = result.getIntValue("no");
            SignResult signResult = WEB_CODE_MAP.getOrDefault(no, SignResult.FATAL);
            switch (signResult) {
                case SUCCESS:
                    LOGGER.info("{}: 签到成功 (web)", kw);
                    break;
                case ALREADY_SIGNED:
                    LOGGER.info("{}: 今日已签到 (web)", kw);
                    break;
                case RETRYABLE:
                    LOGGER.debug("{}: web签到暂时失败，将重试, no={}, error={}", kw, no, result.getString("error"));
                    break;
                default:
                    LOGGER.debug("{}: web签到失败, no={}, error={}", kw, no, result.getString("error"));
                    break;
            }
            return signResult;
        } catch (Exception e) {
            LOGGER.debug("{}: signWeb 异常，将重试 -- {}", kw, e.getMessage());
            return SignResult.RETRYABLE;
        }
    }

    /**
     * 开始进行签到，每一轮将所有未签到的贴吧进行签到，一共进行 5 轮，全部签到完提前结束。
     * 优先走客户端接口（经验值更高），失败时回退到 web 端接口。
     * 已签到、贴吧失效等非重试场景不算作失败，不会继续重试。
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
        int flag = 5;
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

                    // 优先使用客户端接口
                    SignResult result = signClient(s);

                    // 客户端未完成签到时，尝试 web 端兜底
                    if (result != SignResult.SUCCESS && result != SignResult.ALREADY_SIGNED) {
                        SignResult webResult = signWeb(rotation);
                        // 取"更好"的结果（SUCCESS > ALREADY_SIGNED > RETRYABLE > FATAL）
                        result = SignResult.best(result, webResult);                    }

                    switch (result) {
                        case SUCCESS:
                            iterator.remove();
                            success.add(rotation);
                            failed.remove(rotation);
                            break;
                        case ALREADY_SIGNED:
                            iterator.remove();
                            alreadySigned.add(rotation);
                            failed.remove(rotation);
                            break;
                        case RETRYABLE:
                            // 留在 follow 队列中，下一轮继续重试
                            LOGGER.warn("{}: 签到暂时失败，下一轮重试", rotation);
                            failed.add(rotation);
                            break;
                        case FATAL:
                        default:
                            iterator.remove();
                            failed.add(rotation);
                            LOGGER.warn("{}: 签到失败（不再重试）", rotation);
                            break;
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
        String loginStatus = loggedIn ? "已登录" : "未登录（请检查 BDUSS/STOKEN）";
        int realFailed = failed.size();
        String description = "登录状态: " + loginStatus + "\n\n";
        description += "共 " + followNum + " 贴吧\n";
        description += "新签到: " + success.size() + "\n";
        description += "已签到(跳过): " + alreadySigned.size() + "\n";
        description += "失败: " + realFailed + "\n";
        description += "失效: " + invalid.size();
        if (realFailed > 0) {
            List<String> failedList = new ArrayList<>(failed);
            int max = Math.min(failedList.size(), 20);
            description += "\n失败贴吧: " + failedList.subList(0, max);
            if (failedList.size() > max) {
                description += "...等" + failedList.size() + "个";
            }
        }

        try {
            String token = sckey;
            String title = URLEncoder.encode("百度贴吧自动签到", "UTF-8");
            String content = URLEncoder.encode(description, "UTF-8");
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
