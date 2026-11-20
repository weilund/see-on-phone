/**
 * probe-opencode.mjs —— OpenCode Go 端点的**免 key 体检**。
 *
 *   node scripts/probe-opencode.mjs
 *   OPENCODE_GO_KEY=... node scripts/probe-opencode.mjs   # 有 key 就顺便验一次真请求
 *
 * 为什么值得单独一个脚本：Go 的报错很"前置"—— 少了某个头，它在**验 key 之前**
 * 就把你挡了。用户看到"401"会以为 key 错，其实是漏了个头。这个脚本把顺序钉死：
 *
 *   1) `GET /models` 通              → 端点和域名是对的
 *   2) 不带 `x-opencode-session` → 400 MissingSessionID
 *   3) 带上 session、不带 key     → 401 authentication_error
 *   4) 有 key → 200（只在给了 OPENCODE_GO_KEY 时跑）
 *
 * 它**不打印 key**，也不落盘。
 */

const BASE = process.env.OPENCODE_GO_BASE || 'https://opencode.ai/inference/go/openai/v1';
const KEY = process.env.OPENCODE_GO_KEY || '';
const SESSION = 'probe-' + Math.random().toString(16).slice(2, 10);

let fail = 0;

function row(name, ok, detail) {
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? '  —— ' + detail : ''}`);
  if (!ok) fail += 1;
}

async function post(headers, body) {
  const res = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 非 JSON 也要能看原文 */
  }
  return { status: res.status, json, text };
}

/** 把各种错误形状拍平成一句话（Go 的 error 有时嵌两层）。 */
function why(json, text) {
  if (!json) return text.slice(0, 120);
  const e = json.error || json;
  return [e.type, e.message].filter(Boolean).join(': ').slice(0, 160) || text.slice(0, 120);
}

const body = {
  model: 'deepseek-v4.1-flash',
  messages: [{ role: 'user', content: 'ping' }],
  max_tokens: 1,
};

console.log(`端点 ${BASE}\n`);

// 1) 模型列表
try {
  const res = await fetch(`${BASE}/models`);
  const json = await res.json().catch(() => null);
  const n = json?.data?.length ?? 0;
  row('GET /models', res.status === 200 && n > 0, `${res.status}，${n} 个模型`);
  if (n > 0 && !json.data.some((m) => m.id === 'deepseek-v4.1-flash')) {
    console.log('   ⚠️ 列表里没有 deepseek-v4.1-flash（App 的 Go 默认模型），可能已改名');
  }
} catch (err) {
  row('GET /models', false, String(err));
}

// 2) 缺 session
try {
  const r = await post({ authorization: `Bearer ${KEY || 'sk-probe'}` }, body);
  const type = r.json?.error?.type ?? r.json?.type;
  row('缺 x-opencode-session 被拒', r.status === 400 && type === 'MissingSessionID', why(r.json, r.text));
} catch (err) {
  row('缺 x-opencode-session 被拒', false, String(err));
}

// 3) 有 session、无 key
try {
  const r = await post({ 'x-opencode-session': SESSION }, body);
  row('有 session、无 key → 401', r.status === 401, why(r.json, r.text));
} catch (err) {
  row('有 session、无 key → 401', false, String(err));
}

// 4) 有 key（可选）
if (KEY) {
  try {
    const r = await post(
      { 'x-opencode-session': SESSION, authorization: `Bearer ${KEY}` },
      { ...body, max_tokens: 8 },
    );
    const ok = r.status === 200 && r.json?.choices?.length > 0;
    row('带 key 真请求', ok, ok ? `model=${r.json.model}` : why(r.json, r.text));
    // Go 的计费靠 usage.cost（流式要 stream_options.include_usage，见 App 的实现）
    if (ok && r.json?.usage?.cost != null) {
      console.log(`   💰 本次 $${r.json.usage.cost}`);
    }
  } catch (err) {
    row('带 key 真请求', false, String(err));
  }
} else {
  console.log('⏭  跳过真请求（没给 OPENCODE_GO_KEY）');
}

console.log(`\n${fail === 0 ? 'PASS' : 'FAIL'}${fail ? ` ${fail} 项没过` : ''}`);
process.exit(fail === 0 ? 0 : 1);
