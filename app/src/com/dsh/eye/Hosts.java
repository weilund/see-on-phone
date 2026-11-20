package com.dsh.eye;

import java.util.Locale;

/**
 * **端点 → 服务商**的唯一判定点。纯 Java、无 android 依赖（`Caps` 的离线穷举要能用）。
 *
 * ## 为什么按 host 判，而不是让用户选一个枚举
 *
 * 用户改的是"接口地址"（设置页就一个 base 输入框）。只要填了 OpenCode Go 的地址，
 * 就应该自动按 Go 的规矩发请求 —— 再让他在另一个开关里重复声明一次"我现在用的是 Go"，
 * 就多了一个"两处不一致"的状态（地址填 Go、开关还是 DeepSeek，请求头就对不上）。
 *
 * ## 判定规则
 *
 * ```
 * opencode.ai / *.opencode.ai   → OPENCODE_GO
 * *.deepseek.com                → DEEPSEEK
 * 其它（含空、局域网、自己搭的） → OTHER
 * ```
 *
 * `OTHER` 按 DeepSeek 的请求形状走：以 OpenAI 兼容为最大公约数，用户自建网关
 * 通常也认这套；要更宽松就再加分支，别在 `Brain` 里散落 `if (host…)`。
 */
final class Hosts {

    private Hosts() { }

    /** 三个已知的服务商。`OTHER` = 用户自填的端点（按 DeepSeek 那套发）。 */
    enum Kind { DEEPSEEK, OPENCODE_GO, OTHER }

    /** OpenCode 的域名（`opencode.ai` 与旧域名都落在这个 host 上）。 */
    static final String OPENCODE = "opencode.ai";

    static Kind of(String base) {
        String host = host(base);
        if (host.length() == 0) return Kind.OTHER;
        if (host.equals(OPENCODE) || host.endsWith("." + OPENCODE)) return Kind.OPENCODE_GO;
        if (host.equals("deepseek.com") || host.endsWith(".deepseek.com")) return Kind.DEEPSEEK;
        return Kind.OTHER;
    }

    /** 是不是 OpenCode Go（请求头/请求体要走它的规矩）。 */
    static boolean go(String base) { return of(base) == Kind.OPENCODE_GO; }

    /** 给人看的名字（设置页副标题 / `/config`）。 */
    static String label(Kind k) {
        switch (k) {
            case OPENCODE_GO: return "OpenCode Go";
            case DEEPSEEK:    return "DeepSeek";
            default:          return "自定义";
        }
    }

    /** 落盘 / 日志用的稳定短名。 */
    static String key(Kind k) {
        switch (k) {
            case OPENCODE_GO: return "go";
            case DEEPSEEK:    return "deepseek";
            default:          return "other";
        }
    }

    /**
     * 从 URL 里抠 host。**手写解析而不是 `java.net.URI`**：
     * Android 的 `URI` 对 `http://127.0.0.1:7788/v1` 这种带端口、以及没有 scheme 的
     * `api.deepseek.com` 两种写法行为不一致，而这里要的只是"小写主机名"。
     */
    static String host(String base) {
        if (base == null) return "";
        String s = base.trim().toLowerCase(Locale.ROOT);
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }
}
