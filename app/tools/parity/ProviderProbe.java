package com.dsh.eye;

/**
 * `Hosts` / `Caps` 的离线穷举测（**不碰 android，不需要模拟器**）。
 *
 * 为什么要单独一个可执行类而不是 `test/` 里的 JS 测：`Caps.plan` 是
 * "这次请求到底发什么"的唯一出口，provider × 模式 × 档位 × 模型 × 文档 × 链接
 * 的组合有几百种，跑一遍比读一遍可靠。它是当年 `T1Test` 的替代（那个类已不在仓库）。
 *
 * ## 怎么跑
 *
 * ```bash
 * cd app
 * javac -encoding UTF-8 -d /tmp/probe-probe \
 *   tools/parity/ProviderProbe.java src/com/dsh/eye/Hosts.java src/com/dsh/eye/Caps.java src/com/dsh/eye/Err.java
 * java -cp /tmp/probe-probe com.dsh.eye.ProviderProbe
 * ```
 *
 * 退出码非 0 = 有断言没过（CI / 手工都能用）。
 */
public final class ProviderProbe {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        hosts();
        plans();
        System.out.println("\n" + (fail == 0 ? "PASS" : "FAIL") + "  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- Hosts

    private static void hosts() {
        check("deepseek 官方", Hosts.of("https://api.deepseek.com"), Hosts.Kind.DEEPSEEK);
        check("deepseek 带路径", Hosts.of("https://api.deepseek.com/v1"), Hosts.Kind.DEEPSEEK);
        check("deepseek 大写+空格", Hosts.of("  HTTPS://API.DeepSeek.com  "), Hosts.Kind.DEEPSEEK);
        check("go 官方", Hosts.of("https://opencode.ai/inference/go/openai/v1"), Hosts.Kind.OPENCODE_GO);
        check("go 旧域名", Hosts.of("https://opencode.ai/zen/go/v1"), Hosts.Kind.OPENCODE_GO);
        check("go 裸 host", Hosts.of("opencode.ai"), Hosts.Kind.OPENCODE_GO);
        check("go 带端口", Hosts.of("http://opencode.ai:8080/x"), Hosts.Kind.OPENCODE_GO);
        check("本地/自建", Hosts.of("http://127.0.0.1:7788/v1"), Hosts.Kind.OTHER);
        check("空", Hosts.of(""), Hosts.Kind.OTHER);
        check("null", Hosts.of(null), Hosts.Kind.OTHER);
        eq("go() 正", Hosts.go("https://opencode.ai/x"), true);
        eq("go() 负", Hosts.go("https://api.deepseek.com"), false);
        // 相似域名不能被误判成 opencode.ai
        check("仿冒域名", Hosts.of("https://notopencode.ai/x"), Hosts.Kind.OTHER);
    }

    // ---------------------------------------------------------------- Caps.plan

    private static final String[] MODELS = {
        "deepseek-flash", "deepseek-v4-pro", "deepseek-v4.1-flash",
        "glm-5.3-flash", "glm-5.3", "kimi-k3", "hy3", "longcat-2.0",
    };
    private static final String[] EFFORTS = { "auto", "high", "max", "off" };
    private static final Caps.Mode[] MODES = { Caps.Mode.CHAT, Caps.Mode.AGENT, Caps.Mode.WEB };

    private static void plans() {
        for (Hosts.Kind prov : Hosts.Kind.values()) {
            for (Caps.Mode m : MODES) {
                for (String eff : EFFORTS) {
                    for (String model : MODELS) {
                        for (boolean docs : new boolean[] { false, true }) {
                            for (boolean web : new boolean[] { false, true }) {
                                Caps.Plan p = Caps.plan(m, eff, model, docs, true, true, true, web, prov);
                                String tag = "prov=" + Hosts.key(prov) + " m=" + m.key
                                    + " eff=" + eff + " model=" + model
                                    + " docs=" + docs + " web=" + web;
                                // ① 硬不变式：两套工具永不混用
                                eq("永不相混 " + tag, Caps.mixed(p), false);
                                // ② 两边都绝不能出现"被 block 又同时允许发送"
                                if (p.blocked() && (p.allowWebSearch || p.allowDocs)) {
                                    eq("blocked 时不得还带能力 " + tag, true, false);
                                }
                                if (prov == Hosts.Kind.OPENCODE_GO) {
                                    // ③ Go 没有服务端工具 / 标题 / 文档上传
                                    eq("go 无服务端搜索 " + tag, p.allowWebSearch, false);
                                    eq("go 无读网页 " + tag, p.allowReadUrl, false);
                                    eq("go 无 title " + tag, p.title, false);
                                    eq("go 无文档 " + tag, p.allowDocs, false);
                                    eq("go 一定是 CLIENT " + tag, p.kind, Caps.Plan.Kind.CLIENT);
                                    // ④ Go 的联网 = hybrid（聊天也支持）
                                    eq("go 联网=hybrid " + tag, p.hybrid, web);
                                    // ⑤ Go 下「聊天+联网」不被归一成 WEB
                                    if (m == Caps.Mode.CHAT && web) {
                                        eq("go 聊天+联网仍是 CHAT " + tag, p.mode, Caps.Mode.CHAT);
                                    }
                                    // ⑥ 带文档 → 明确拦掉
                                    if (docs) eq("go 带文档被拦 " + tag, p.blocked(), true);
                                    if (docs) eq("go 文档文案码 " + tag, p.blocked, Err.Code.DOC_UNSUPPORTED);
                                } else if (prov == Hosts.Kind.DEEPSEEK) {
                                    // ⑦ DeepSeek 的历史行为一点没动
                                    if (m == Caps.Mode.WEB) {
                                        eq("ds 联网=SERVER " + tag, p.kind, Caps.Plan.Kind.SERVER);
                                        eq("ds 联网带服务端搜索 " + tag, p.allowWebSearch, true);
                                    }
                                    if (m == Caps.Mode.CHAT && web) {
                                        eq("ds 聊天+联网归一成 WEB " + tag, p.mode, Caps.Mode.WEB);
                                    }
                                }
                                // ⑧ 视觉：pro 与纯文本模型不能图
                                if (Caps.isPro(model) || Caps.isVisionless(model)) {
                                    eq("无视觉 " + tag, p.allowImages, false);
                                }
                                // ⑨ 档位：off 不思考，high/max 带 effort
                                if ("off".equals(eff)) {
                                    eq("off 不思考 " + tag, p.thinking, false);
                                    eq("off 发 temperature " + tag, p.sendTemperature, true);
                                } else if ("high".equals(eff) || "max".equals(eff)) {
                                    eq("high/max 带 effort " + tag, p.thinking, true);
                                    eq("high/max effort 值 " + tag, eff, p.effort);
                                } else {
                                    eq("auto 不写 effort " + tag, p.effort, null);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void check(String name, Hosts.Kind got, Hosts.Kind want) {
        eq(name, got, want);
    }

    private static void eq(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) {
            pass += 1;
        } else {
            fail += 1;
            System.out.println("FAIL " + name + "  got=" + got + " want=" + want);
        }
    }
}
