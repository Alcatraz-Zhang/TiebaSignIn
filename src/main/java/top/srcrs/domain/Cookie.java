package top.srcrs.domain;

/**
 * 存入用户所填写的 Cookie 信息
 * @author srcrs
 * @Time 2020-10-31
 */
public class Cookie {
    private static final Cookie cookie = new Cookie();
    private String BDUSS;
    /** 百度新版鉴权所需的 STOKEN，可选。未设置时退化为仅 BDUSS（向后兼容）。 */
    private String STOKEN;
    private Cookie(){};

    public static Cookie getInstance() {
        return cookie;
    }

    public String getBDUSS() {
        return BDUSS;
    }

    public void setBDUSS(String BDUSS) {
        this.BDUSS = BDUSS;
    }

    public String getStoken() {
        return STOKEN;
    }

    public void setStoken(String stoken) {
        this.STOKEN = stoken;
    }

    /**
     * 返回 Cookie 字符串。
     * 若 STOKEN 已设置则同时携带，否则仅返回 BDUSS（向后兼容旧调用方式）。
     */
    public String getCookie() {
        if (STOKEN != null && !STOKEN.isEmpty()) {
            return "BDUSS=" + BDUSS + "; STOKEN=" + STOKEN;
        }
        return "BDUSS=" + BDUSS;
    }
}
