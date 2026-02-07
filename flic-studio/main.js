// main.js
/**
 * Flic Hub Studio - Hubitat Integration V1.0
 *
 * By Robin Winbourne
 *
 * IMPORTANT (new virtual device handling):
 * - A Twist can control MANY “virtual device types” in Flic:
 *     Light:   brightness, colorTemperature, hue, saturation   (0..1)
 *     Speaker: volume                                         (0..1)
 *     Blind:   position                                       (0..1)
 *
 * - In Studio, we MUST forward whatever keys arrive in `values` (0..1) to Hubitat.
 *   Do NOT “force everything into level 0..100”.
 *
 * Virtual device naming (Studio side):
 * - Master (sel=0):        flicN-dimmer0   OR flicN-speaker0   OR flicN-blinds0
 * - Secondary (sel=1):     flicN-dimmer1   OR flicN-speaker1   OR flicN-blinds1
 * - Selector positions:    flicN-dimmer1..11  OR flicN-speaker1..11  OR flicN-blinds1..11
 *
 * Disambiguation note:
 * - When meta.pushSelect === true (selector mode), sel=0..11 click events are driven by actionMessage.
 * - When meta.pushSelect !== true (non-selector mode), we ignore actionMessage and use raw push events only (sel=0).
 *
 * TCP commands:
 * - PING / STATUS / LIST_BUTTONS / SET_CONFIG / CLEAR_CONFIG / UPDATE_VD / UPDATE_VD_STATE / HTTP_PROXY
 *
 * Notes about Flic Studio runtime:
 * - Not full Node.js: do NOT use https/url/http.request/Buffer.
 * - Use Studio-provided http.makeRequest().
 */

const net = require("net");
const http = require("http");
const buttons = require("buttons");
const flicapp = require("flicapp");
const datastore = require("datastore");

const LISTEN_PORT = 46321;

// ---------- tuning knobs ----------
const TWIST_IDLE_SEND_MS = 250; // debounce/merge virtual updates
const RELEASE_ARM_TIMEOUT_MS = 15000; // safety guard: "armed for release" expires after 15s
const VD_MIN_DELTA_01 = 0.02; // ignore tiny jitter (>=2% change on 0..1 range required to send)

// HTTP proxy tuning
const HTTP_PROXY_TIMEOUT_MS = 3000; // keep tight; local LAN should be fast
const HTTP_PROXY_MAX_BODY_CHARS = 25000;

// ---------- logging knobs (reduced noise) ----------
const LOG = {
  hubitatSend: false,          // log every outbound Hubitat send (noisy with twists)
  hubitatOk: false,            // log every Hubitat ok (noisy with twists)
  hubitatErrors: true,         // keep errors
  httpProxy: true,             // keep proxy recv/done (useful sanity check)
  updateVdFromHubitat: true,   // log UPDATE_VD from Hubitat (usually low volume)
};

function logHubSend(msg) { if (LOG.hubitatSend) console.log(msg); }
function logHubOk(msg) { if (LOG.hubitatOk) console.log(msg); }
function logHubErr(msg) { if (LOG.hubitatErrors) console.log(msg); }
function logProxy(msg) { if (LOG.httpProxy) console.log(msg); }
function logUpdateVd(msg) { if (LOG.updateVdFromHubitat) console.log(msg); }

// ---------- persistence ----------
const DS_KEY = "hubitat_config"; // do not change unless you want to reset stored config

// ---------- runtime config ----------
let hubitat = null; // { base, appId, token }
let devicesByBdaddr = {}; // { "04:..": { bdaddr, flicNum:"1", type:"twist"|"button", enabled:true, pushSelect:true/false } }

// ---------- instance identity ----------
function randId() {
  return Math.random().toString(16).slice(2) + "-" + Date.now().toString(16);
}
const instanceId = randId();

// ---------- utils ----------
function nowIso() {
  return new Date().toISOString();
}

function normalizeIp(ip) {
  if (!ip) return ip;
  if (ip.startsWith("::ffff:")) return ip.substring(7);
  return ip;
}

function sendLine(socket, obj) {
  try {
    socket.write(JSON.stringify(obj) + "\n");
  } catch (e) {}
}

function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== null)
    .map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(obj[k])))
    .join("&");
}

function safeStr(x) {
  return x === undefined || x === null ? "" : String(x);
}

function normBdaddr(bdaddr) {
  return safeStr(bdaddr).trim().toLowerCase();
}

function deviceMetaByBdaddr(bdaddr) {
  const key = normBdaddr(bdaddr);
  return devicesByBdaddr ? devicesByBdaddr[key] : null;
}

function isEnabled(bdaddr) {
  const d = deviceMetaByBdaddr(bdaddr);
  return !!(d && d.enabled !== false);
}

// ---------- Hubitat outbound events ----------
function hubitatEvent(params) {
  if (!hubitat || !hubitat.base || !hubitat.appId || !hubitat.token) return;

  const url =
    `${hubitat.base}/apps/api/${hubitat.appId}/event?` +
    qs({ ...params, access_token: hubitat.token });

  const briefParts = [];
  [
    "bdaddr",
    "type",
    "event",
    "sel",
    "kind",
    "dimmableType",
    "brightness",
    "colorTemperature",
    "hue",
    "saturation",
    "volume",
    "position",
    "value",
  ].forEach((k) => {
    if (params[k] !== undefined) briefParts.push(`${k}=${params[k]}`);
  });
  const brief = briefParts.join(" ");

  logHubSend(`[${nowIso()}] [HUBITAT] send ${brief}`);

  http.makeRequest({ url }, (err, resp) => {
    try {
      if (err) {
        logHubErr(`[${nowIso()}] [HUBITAT] error ${brief} -> ${String(err)}`);
        return;
      }
      const status = (resp && (resp.statusCode || resp.status)) || "(unknown)";
      logHubOk(`[${nowIso()}] [HUBITAT] ok ${brief} status=${status}`);
    } catch (e) {
      logHubErr(`[${nowIso()}] [HUBITAT] response parse error: ${String(e)}`);
    }
  });
}

// ---------- datastore persistence ----------
function loadConfigFromStore(cb) {
  try {
    datastore.get(DS_KEY, (err, result) => {
      if (err) {
        console.log(
          `[${nowIso()}] [CONFIG] load failed datastore.get: ${String(err)}`
        );
        return cb(false);
      }
      if (!result) return cb(false);

      try {
        const obj = JSON.parse(String(result));
        if (obj && obj.hubitat && obj.devicesByBdaddr) {
          hubitat = obj.hubitat;
          devicesByBdaddr = obj.devicesByBdaddr;
          console.log(
            `[${nowIso()}] [CONFIG] loaded: base=${hubitat.base} appId=${hubitat.appId} devices=${Object.keys(devicesByBdaddr).length} instanceId=${instanceId}`
          );
          return cb(true);
        }
        console.log(`[${nowIso()}] [CONFIG] load ignored: missing fields`);
        cb(false);
      } catch (e2) {
        console.log(`[${nowIso()}] [CONFIG] load failed JSON: ${String(e2)}`);
        cb(false);
      }
    });
  } catch (e) {
    console.log(`[${nowIso()}] [CONFIG] load exception: ${String(e)}`);
    cb(false);
  }
}

function saveConfigToStore(cb) {
  try {
    const payload = {
      savedAt: nowIso(),
      instanceId,
      hubitat,
      devicesByBdaddr,
    };

    const json = JSON.stringify(payload);

    datastore.put(DS_KEY, json, (err) => {
      if (err) {
        console.log(
          `[${nowIso()}] [CONFIG] persist failed datastore.put: ${String(err)}`
        );
        return cb && cb(false);
      }
      console.log(`[${nowIso()}] [CONFIG] persisted`);
      cb && cb(true);
    });
  } catch (e) {
    console.log(`[${nowIso()}] [CONFIG] persist exception: ${String(e)}`);
    cb && cb(false);
  }
}

function clearConfig(cb) {
  hubitat = null;
  devicesByBdaddr = {};

  try {
    // Studio datastore does not guarantee a delete API; store empty string to clear.
    datastore.put(DS_KEY, "", (err) => {
      if (err)
        console.log(
          `[${nowIso()}] [CONFIG] clear failed datastore.put: ${String(err)}`
        );
      else console.log(`[${nowIso()}] [CONFIG] cleared`);
      cb && cb(true);
    });
  } catch (e) {
    console.log(`[${nowIso()}] [CONFIG] clear exception: ${String(e)}`);
    cb && cb(false);
  }
}

// ---------- listButtons ----------
function listButtons(cb) {
  try {
    if (typeof buttons.getButtons === "function") {
      if (buttons.getButtons.length >= 1) {
        return buttons.getButtons((err, arr) =>
          cb(err ? String(err) : null, arr || [])
        );
      }
      return cb(null, buttons.getButtons() || []);
    }
    cb("buttons.getButtons not available", null);
  } catch (e) {
    cb(String(e), null);
  }
}

function simplifyButtons(arr) {
  return (arr || []).map((b) => {
    let flicVersion = null;
    let serialNumber = null;

    try {
      const o = JSON.parse(JSON.stringify(b));
      flicVersion = o && typeof o.flicVersion === "number" ? o.flicVersion : null;
      serialNumber = o && o.serialNumber != null ? String(o.serialNumber) : null;
    } catch (e) {}

    return {
      name: b && b.name,
      bdaddr: b && b.bdaddr,
      uuid: b && b.uuid,
      type: b && b.type,
      batteryStatus: b && b.batteryStatus,
      connected: b && b.connected,
      flicVersion,
      serialNumber,
    };
  });
}

// ---------- virtual device id parsing ----------
function parseVirtualDeviceId(vdid) {
  // flicN-dimmerX / flicN-speakerX / flicN-blindsX
  const s = String(vdid || "").trim();
  const m = s.match(/^flic(\d+)-(dimmer|speaker|blinds)(\d{1,2})$/i);
  if (!m) return null;

  const flicNum = String(m[1]);
  const kind = String(m[2]).toLowerCase(); // dimmer | speaker | blinds
  const sel = parseInt(m[3], 10);

  if (!/^\d+$/.test(flicNum)) return null;
  if (!(sel >= 0 && sel <= 11)) return null;

  return { flicNum, kind, sel };
}

// ---------- flicNum index (rebuilt on SET_CONFIG / load) ----------
let flicNumToBdaddrIndex = {}; // { "1": "04:..", ... }

function rebuildFlicNumIndex() {
  const idx = {};
  Object.keys(devicesByBdaddr || {}).forEach((bd) => {
    const d = devicesByBdaddr[bd] || {};
    if (!d.flicNum) return;
    const n = String(d.flicNum).trim();
    if (!/^\d+$/.test(n)) return;
    idx[n] = normBdaddr(bd);
  });
  flicNumToBdaddrIndex = idx;
}

// ---------- release gating w/ timeout ----------
const releaseArmedUntilMs = Object.create(null);

function armRelease(bdaddr) {
  const key = normBdaddr(bdaddr);
  if (!key) return;
  releaseArmedUntilMs[key] = Date.now() + RELEASE_ARM_TIMEOUT_MS;
}

function isReleaseArmed(bdaddr) {
  const key = normBdaddr(bdaddr);
  const until = releaseArmedUntilMs[key] || 0;
  if (until <= 0) return false;

  if (Date.now() > until) {
    releaseArmedUntilMs[key] = 0;
    return false;
  }
  return true;
}

function disarmRelease(bdaddr) {
  const key = normBdaddr(bdaddr);
  if (!key) return;
  releaseArmedUntilMs[key] = 0;
}

// ---------- action message parsing ----------
function parseActionMessage(msg) {
  const s = String(msg || "").trim();
  const m = s.match(/^flic(\d+)-button(\d+)-event(1|2)$/i);
  if (!m) return null;

  const flicNum = String(m[1]);
  const buttonNum = parseInt(m[2], 10); // 0..11
  const which = String(m[3]); // "1"|"2"

  if (!/^\d+$/.test(flicNum)) return null;
  if (!(buttonNum >= 0 && buttonNum <= 11)) return null;

  const event = which === "1" ? "event1" : "event2";
  return { flicNum, sel: buttonNum, event };
}

// ---------- virtual update merge/debounce (Twist -> Hubitat, send native 0..1 values) ----------
const pendingVD = Object.create(null); // key=bdaddr|sel -> { fields... }
const timersVD = Object.create(null); // key -> timeout
const lastSentVD = Object.create(null); // key -> JSON signature to dedupe
const lastSentVDFields = Object.create(null); // key -> {brightness:0..1, ...}

function round6(n) {
  return Math.round(n * 1e6) / 1e6;
}

function pickKnownFields(values) {
  const out = {};
  if (!values || typeof values !== "object") return out;

  [
    "brightness",
    "colorTemperature",
    "hue",
    "saturation",
    "volume",
    "position",
  ].forEach((k) => {
    if (typeof values[k] === "number" && isFinite(values[k])) {
      let v = values[k];
      if (v < 0) v = 0;
      if (v > 1) v = 1;
      out[k] = round6(v);
    }
  });

  return out;
}

function scheduleVirtualUpdateSend(bdaddr, sel, kind, dimmableType, fields) {
  const keyBd = normBdaddr(bdaddr);
  const key = `${keyBd}|${sel}`;

  const cur = pendingVD[key] || {};
  pendingVD[key] = { ...cur, ...fields };

  if (timersVD[key]) {
    clearTimeout(timersVD[key]);
    timersVD[key] = null;
  }

  timersVD[key] = setTimeout(() => {
    timersVD[key] = null;

    if (!hubitat) return;
    if (!isEnabled(keyBd)) return;

    const meta = deviceMetaByBdaddr(keyBd);
    if (!meta) return;

    const t = (meta.type || "twist").toLowerCase();
    if (t !== "twist") return;

    const payload = pendingVD[key];
    if (!payload || Object.keys(payload).length === 0) return;

    const prev = lastSentVDFields[key] || {};
    const filtered = {};
    Object.keys(payload).forEach((k) => {
      const v = payload[k];
      if (typeof v !== "number" || !isFinite(v)) return;

      const p = prev[k];
      if (typeof p === "number" && isFinite(p)) {
        if (Math.abs(v - p) < VD_MIN_DELTA_01) return;
      }
      filtered[k] = v;
    });

    if (Object.keys(filtered).length === 0) return;

    const signatureObj = { kind, dimmableType: dimmableType || "", ...filtered };
    const signature = JSON.stringify(signatureObj);

    if (lastSentVD[key] === signature) return;
    lastSentVD[key] = signature;

    lastSentVDFields[key] = { ...prev, ...filtered };

    hubitatEvent({
      bdaddr: keyBd,
      type: "twist",
      event: "vd",
      sel,
      kind,
      dimmableType: dimmableType || "",
      ...filtered,
    });
  }, TWIST_IDLE_SEND_MS);
}

// ---------- HE -> Studio: apply virtual device state updates ----------
function kindToType(kind) {
  const k = String(kind || "").toLowerCase();
  if (k === "speaker") return "Speaker";
  if (k === "blinds" || k === "blind") return "Blind";
  return "Light"; // dimmer and default
}

// Restrict keys to what Flic expects for each type
function filterValuesByType(type, values01) {
  const out = {};
  if (!values01 || typeof values01 !== "object") return out;

  const t = String(type || "");
  const allowed =
    t === "Speaker"
      ? ["volume"]
      : t === "Blind"
        ? ["position"]
        : ["brightness", "hue", "saturation", "colorTemperature"]; // Light

  allowed.forEach((k) => {
    if (typeof values01[k] === "number" && isFinite(values01[k])) {
      let v = values01[k];
      if (v < 0) v = 0;
      if (v > 1) v = 1;
      out[k] = round6(v);
    }
  });

  return out;
}

function buildVirtualDeviceIdFromRoute(flicNum, kind, sel) {
  const n = String(flicNum || "").trim();
  const k = String(kind || "").toLowerCase();
  const s = parseInt(sel, 10);

  if (!/^\d+$/.test(n)) return null;
  if (!(s >= 0 && s <= 11)) return null;
  if (!(k === "dimmer" || k === "speaker" || k === "blinds")) return null;

  return `flic${n}-${k}${s}`;
}

function applyUpdateVdFromHubitat(msg) {
  // msg: { cmd, bdaddr, sel, kind, dimmableType, brightness/colorTemperature/hue/saturation/volume/position }
  const bdaddr = normBdaddr(msg && msg.bdaddr);
  if (!bdaddr) return { ok: false, err: "missing_bdaddr" };

  const meta = deviceMetaByBdaddr(bdaddr);
  if (!meta) return { ok: false, err: "unknown_bdaddr" };
  if (!isEnabled(bdaddr)) return { ok: false, err: "device_disabled" };

  const t = (meta.type || "twist").toLowerCase();
  if (t !== "twist") return { ok: false, err: "not_a_twist" };

  const sel = parseInt(msg && msg.sel, 10);
  if (!(sel >= 0 && sel <= 11)) return { ok: false, err: "bad_sel" };

  const kind = String((msg && msg.kind) || "").toLowerCase();
  if (!(kind === "dimmer" || kind === "speaker" || kind === "blinds")) {
    return { ok: false, err: "bad_kind", need: ["dimmer", "speaker", "blinds"] };
  }

  const flicNum = String(meta.flicNum || "").trim();
  const vdid = buildVirtualDeviceIdFromRoute(flicNum, kind, sel);
  if (!vdid) return { ok: false, err: "bad_virtualDeviceId" };

  // Prefer explicit dimmableType if valid, else infer from kind
  let type = String((msg && msg.dimmableType) || "");
  if (!(type === "Light" || type === "Blind" || type === "Speaker")) {
    type = kindToType(kind);
  }

  const picked = pickKnownFields(msg); // clamps + rounds 0..1
  const values = filterValuesByType(type, picked);
  if (Object.keys(values).length === 0) return { ok: false, err: "no_values" };

  logUpdateVd(
    `[${nowIso()}] [UPDATE_VD] from Hubitat bdaddr=${bdaddr} flicNum=${flicNum} id=${vdid} type=${type} values=${JSON.stringify(values)}`
  );

  try {
    // Official API: tell Flic the remote state changed
    flicapp.virtualDeviceUpdateState(type, vdid, values);
    return { ok: true, reply: "UPDATE_VD_APPLIED", virtualDeviceId: vdid, type, values };
  } catch (e) {
    return { ok: false, err: "virtualDeviceUpdateState_failed", detail: String(e) };
  }
}

// ---------- HTTP proxy (Hubitat -> webCoRE via Studio) ----------
// Keep minimal: http:// only, host allow-list (same host[:port] as hubitat.base, plus localhost/127.0.0.1).
function hostFromHttpUrl(urlStr) {
  const s = String(urlStr || "").trim();
  const m = s.match(/^http:\/\/([^\/?#]+)(?:[\/?#]|$)/i);
  if (!m) return null;
  return String(m[1] || "").trim().toLowerCase(); // host[:port]
}

function isAllowedProxyUrl(urlStr) {
  if (!hubitat || !hubitat.base) return false;

  const allowed = hostFromHttpUrl(hubitat.base);
  if (!allowed) return false;

  const host = hostFromHttpUrl(urlStr);
  if (!host) return false;

  if (host === "localhost" || host === "127.0.0.1") return true;
  return host === allowed;
}

function httpGetAsText(urlStr, timeoutMs, maxBodyChars) {
  return new Promise((resolve) => {
    const url = String(urlStr || "").trim();
    if (!url) return resolve({ ok: false, status: 0, err: "empty_url" });
    if (!/^http:\/\//i.test(url)) {
      return resolve({ ok: false, status: 0, err: "only_http_supported" });
    }

    let done = false;
    function finish(obj) {
      if (done) return;
      done = true;
      resolve(obj);
    }

    const t = setTimeout(() => {
      finish({ ok: false, status: 0, err: "timeout" });
    }, timeoutMs);

    try {
      http.makeRequest({ url }, (err, resp) => {
        clearTimeout(t);

        if (err) return finish({ ok: false, status: 0, err: String(err) });

        const status = (resp && (resp.statusCode || resp.status)) || 0;

        // Studio may use resp.body or resp.data
        let body =
          (resp && resp.body != null ? String(resp.body) : null) ||
          (resp && resp.data != null ? String(resp.data) : "") ||
          "";

        let truncated = false;
        if (body.length > maxBodyChars) {
          body = body.substring(0, maxBodyChars);
          truncated = true;
        }

        finish({ ok: true, status, body, truncated });
      });
    } catch (e) {
      clearTimeout(t);
      finish({ ok: false, status: 0, err: String(e) });
    }
  });
}

// ---------- Studio listeners ----------

// 1) Virtual device updates (Twist -> Hubitat)
flicapp.on("virtualDeviceUpdate", (metaData, values) => {
  try {
    const vd = metaData && metaData.virtualDeviceId;
    if (vd === undefined || vd === null) return;

    const parsed = parseVirtualDeviceId(vd);
    if (!parsed) return;

    const { flicNum, kind, sel: rawSel } = parsed;

    const bdaddr = flicNumToBdaddrIndex[flicNum];
    if (!bdaddr) return;

    const meta = deviceMetaByBdaddr(bdaddr);
    if (!meta) return;
    if (!isEnabled(bdaddr)) return;

    const t = (meta.type || "twist").toLowerCase();
    if (t !== "twist") return;

    const dimmableType =
      metaData && metaData.dimmableType ? String(metaData.dimmableType) : "";

    const fields = pickKnownFields(values);
    if (Object.keys(fields).length === 0) return;

    const sel = rawSel; // keep raw sel
    scheduleVirtualUpdateSend(bdaddr, sel, kind, dimmableType, fields);
  } catch (e) {
    // silent
  }
});

// 2) Action messages: ONLY for pushSelect twist clicks
flicapp.on("actionMessage", (message) => {
  try {
    const parsed = parseActionMessage(message);
    if (!parsed) return;

    const bdaddr = flicNumToBdaddrIndex[parsed.flicNum];
    if (!bdaddr) return;

    if (!hubitat) return;
    if (!isEnabled(bdaddr)) return;

    const meta = deviceMetaByBdaddr(bdaddr);
    if (!meta) return;

    const t = (meta.type || "twist").toLowerCase();
    if (t !== "twist") return;

    if (meta.pushSelect !== true) return;

    if (!(parsed.sel >= 0 && parsed.sel <= 11)) return;

    hubitatEvent({
      bdaddr,
      type: "twist",
      event: parsed.event,
      sel: parsed.sel,
      value: "1",
    });
  } catch (e) {
    // silent
  }
});

// 3) Raw click/hold events (buttons + twist when pushSelect=false)
function safeBool(x) {
  return !!x;
}

buttons.on("buttonSingleOrDoubleClickOrHold", (data) => {
  try {
    const bdaddr = normBdaddr(data && data.bdaddr);
    if (!bdaddr) return;

    if (!hubitat) return;
    if (!isEnabled(bdaddr)) return;

    const meta = deviceMetaByBdaddr(bdaddr);
    if (!meta) return;

    const t = (meta.type || "button").toLowerCase();
    if (!(t === "button" || t === "twist")) return;

    const isSingle = safeBool(data.isSingleClick);
    const isDouble = safeBool(data.isDoubleClick);
    const isHold = safeBool(data.isHold);

    let event = null;
    if (isSingle) event = "event1";
    else if (isDouble) event = "event2";
    else if (isHold) event = "eventH";
    else return;

    // In selector mode, twist click events are driven exclusively by actionMessage.
    if (t === "twist" && meta.pushSelect === true) return;

    if (event === "eventH") {
      armRelease(bdaddr);
    }

    hubitatEvent({
      bdaddr,
      type: t,
      event,
      sel: 0,
      value: "1",
    });
  } catch (e) {
    // silent
  }
});

// 4) Release events (buttonUp), but only if armed by a prior hold
buttons.on("buttonUp", (data) => {
  try {
    const bdaddr = normBdaddr(data && data.bdaddr);
    if (!bdaddr) return;

    if (!hubitat) return;
    if (!isEnabled(bdaddr)) return;

    const meta = deviceMetaByBdaddr(bdaddr);
    if (!meta) return;

    const t = (meta.type || "button").toLowerCase();
    if (!(t === "button" || t === "twist")) return;

    // In pushSelect=true, do NOT send release for twist
    if (t === "twist" && meta.pushSelect === true) return;

    if (!isReleaseArmed(bdaddr)) return;

    disarmRelease(bdaddr);

    hubitatEvent({
      bdaddr,
      type: t,
      event: "release",
      sel: 0,
    });
  } catch (e) {
    // silent
  }
});

// ---------- TCP server ----------
console.log("=== Flic → Hubitat starting ===");
console.log(nowIso());
console.log("instanceId:");
console.log(instanceId);
console.log("TCP server listening port:");
console.log(LISTEN_PORT);
console.log("cmds: PING/STATUS/LIST_BUTTONS/SET_CONFIG/CLEAR_CONFIG/UPDATE_VD/UPDATE_VD_STATE/HTTP_PROXY");

// Load persisted config
loadConfigFromStore((ok) => {
  if (ok) rebuildFlicNumIndex();
});

const server = net.createServer((socket) => {
  const remoteIp = normalizeIp(socket.remoteAddress);
  const remotePort = socket.remotePort;

  sendLine(socket, {
    ok: true,
    hello: "flic-hub-hubitat",
    version: 1,
    time: nowIso(),
    port: LISTEN_PORT,
    instanceId,
    configured: !!hubitat,
    deviceCount: Object.keys(devicesByBdaddr || {}).length,
    pairedIp: remoteIp || null,
    pairedPort: remotePort || null,
  });

  let buffer = "";

  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");

    while (true) {
      const idx = buffer.indexOf("\n");
      if (idx < 0) break;

      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;

      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        sendLine(socket, {
          ok: false,
          err: "bad_json",
          detail: String(e),
          instanceId,
        });
        continue;
      }

      const cmd = String(msg.cmd || "").toUpperCase();

      if (cmd === "PING") {
        sendLine(socket, {
          ok: true,
          reply: "PONG",
          time: nowIso(),
          instanceId,
        });
        continue;
      }

      if (cmd === "STATUS") {
        sendLine(socket, {
          ok: true,
          status: "running",
          time: nowIso(),
          instanceId,
          configured: !!hubitat,
          hubitat: hubitat ? { base: hubitat.base, appId: hubitat.appId } : null,
          deviceCount: Object.keys(devicesByBdaddr || {}).length,
          bdaddrs: Object.keys(devicesByBdaddr || {}).sort(),
          flicNums: Object.keys(flicNumToBdaddrIndex || {}).sort(),
        });
        continue;
      }

      if (cmd === "CLEAR_CONFIG") {
        clearConfig(() => {
          rebuildFlicNumIndex();

          Object.keys(releaseArmedUntilMs).forEach((k) => {
            releaseArmedUntilMs[k] = 0;
          });

          Object.keys(pendingVD).forEach((k) => delete pendingVD[k]);
          Object.keys(timersVD).forEach((k) => {
            try {
              clearTimeout(timersVD[k]);
            } catch (e) {}
            delete timersVD[k];
          });
          Object.keys(lastSentVD).forEach((k) => delete lastSentVD[k]);
          Object.keys(lastSentVDFields).forEach((k) => delete lastSentVDFields[k]);

          sendLine(socket, {
            ok: true,
            reply: "CONFIG_CLEARED",
            time: nowIso(),
            instanceId,
          });
        });
        continue;
      }

      if (cmd === "LIST_BUTTONS") {
        listButtons((err, arr) => {
          if (err) {
            sendLine(socket, {
              ok: false,
              err: "list_buttons_failed",
              detail: err,
              instanceId,
            });
            return;
          }
          sendLine(socket, {
            ok: true,
            rawCount: (arr || []).length,
            buttons: simplifyButtons(arr),
            instanceId,
          });
        });
        continue;
      }

      if (cmd === "SET_CONFIG") {
        try {
          const hb = msg.hubitat || {};
          if (!hb.base || !hb.appId || !hb.token) {
            sendLine(socket, {
              ok: false,
              err: "missing_hubitat",
              need: ["hubitat.base", "hubitat.appId", "hubitat.token"],
              instanceId,
            });
            continue;
          }

          const devs = msg.devices || {};
          if (typeof devs !== "object") {
            sendLine(socket, { ok: false, err: "bad_devices_map", instanceId });
            continue;
          }

          hubitat = {
            base: String(hb.base).replace(/\/+$/, ""),
            appId: String(hb.appId),
            token: String(hb.token),
          };

          const normalized = {};
          Object.keys(devs).forEach((k) => {
            const bdaddr = normBdaddr(k);
            const v = devs[k] || {};
            if (!bdaddr) return;

            const flicNum =
              v.flicNum !== undefined && v.flicNum !== null
                ? String(v.flicNum).trim()
                : "";
            if (!/^\d+$/.test(flicNum)) return;

            normalized[bdaddr] = {
              bdaddr,
              flicNum,
              type: v.type ? String(v.type).toLowerCase() : "button",
              enabled: v.enabled === undefined ? true : !!v.enabled,
              pushSelect: v.pushSelect === true,
            };
          });

          devicesByBdaddr = normalized;
          rebuildFlicNumIndex();

          const current = new Set(Object.keys(devicesByBdaddr || {}));
          Object.keys(releaseArmedUntilMs).forEach((k) => {
            if (!current.has(k)) delete releaseArmedUntilMs[k];
            else releaseArmedUntilMs[k] = 0;
          });

          Object.keys(pendingVD).forEach((k) => {
            const bd = k.split("|")[0];
            if (!current.has(bd)) delete pendingVD[k];
          });
          Object.keys(lastSentVD).forEach((k) => {
            const bd = k.split("|")[0];
            if (!current.has(bd)) delete lastSentVD[k];
          });
          Object.keys(lastSentVDFields).forEach((k) => {
            const bd = k.split("|")[0];
            if (!current.has(bd)) delete lastSentVDFields[k];
          });

          console.log(
            `[${nowIso()}] [CONFIG] SET_CONFIG received: base=${hubitat.base} appId=${hubitat.appId} devices=${Object.keys(devicesByBdaddr).length}`
          );

          saveConfigToStore(() => {
            sendLine(socket, {
              ok: true,
              reply: "CONFIG_SET",
              time: nowIso(),
              instanceId,
              configured: true,
              deviceCount: Object.keys(devicesByBdaddr).length,
              bdaddrs: Object.keys(devicesByBdaddr).sort(),
              flicNums: Object.keys(flicNumToBdaddrIndex).sort(),
            });
          });
        } catch (e) {
          sendLine(socket, {
            ok: false,
            err: "set_config_exception",
            detail: String(e),
            instanceId,
          });
        }
        continue;
      }

      // HE -> Studio virtual device state push
      if (cmd === "UPDATE_VD" || cmd === "UPDATE_VD_STATE") {
        const res = applyUpdateVdFromHubitat(msg);
        sendLine(socket, { ...res, time: nowIso(), instanceId });
        continue;
      }

      // Hubitat -> Studio -> Hubitat (webCoRE) proxy
      if (cmd === "HTTP_PROXY") {
        const nonce = safeStr(msg.nonce);
        const method = String(msg.method || "GET").toUpperCase();
        const url = safeStr(msg.url).trim();

        if (!nonce) {
          sendLine(socket, {
            ok: false,
            cmd: "HTTP_PROXY_RESULT",
            nonce: "",
            status: 0,
            err: "missing_nonce",
            time: nowIso(),
            instanceId,
          });
          continue;
        }

        if (method !== "GET") {
          sendLine(socket, {
            ok: false,
            cmd: "HTTP_PROXY_RESULT",
            nonce,
            status: 0,
            err: "only_get_supported",
            time: nowIso(),
            instanceId,
          });
          continue;
        }

        if (!url) {
          sendLine(socket, {
            ok: false,
            cmd: "HTTP_PROXY_RESULT",
            nonce,
            status: 0,
            err: "missing_url",
            time: nowIso(),
            instanceId,
          });
          continue;
        }

        if (!hubitat || !hubitat.base) {
          sendLine(socket, {
            ok: false,
            cmd: "HTTP_PROXY_RESULT",
            nonce,
            status: 0,
            err: "not_configured",
            time: nowIso(),
            instanceId,
          });
          continue;
        }

        if (!isAllowedProxyUrl(url)) {
          sendLine(socket, {
            ok: false,
            cmd: "HTTP_PROXY_RESULT",
            nonce,
            status: 0,
            err: "proxy_denied_host",
            allowedHost: hostFromHttpUrl(hubitat.base),
            time: nowIso(),
            instanceId,
          });
          continue;
        }

        logProxy(`[${nowIso()}] [HTTP_PROXY] recv nonce=${nonce} url=${url}`);

        // Fire the GET. We still send HTTP_PROXY_RESULT, but keep the payload minimal unless it failed.
        httpGetAsText(url, HTTP_PROXY_TIMEOUT_MS, HTTP_PROXY_MAX_BODY_CHARS).then((r) => {
          logProxy(
            `[${nowIso()}] [HTTP_PROXY] done nonce=${nonce} ok=${r.ok} status=${r.status} err=${r.err || ""}`
          );

          // "Send and forget" style: don't send big bodies back unless there was an error.
          const includeBody = !(r && r.ok === true && (r.status === 200 || r.status === 204));
          sendLine(socket, {
            ok: r.ok === true,
            cmd: "HTTP_PROXY_RESULT",
            nonce,
            status: r.status || 0,
            body: includeBody ? (r.body || "") : "",
            truncated: includeBody ? (r.truncated === true) : false,
            err: r.ok ? "" : (r.err || "error"),
            time: nowIso(),
            instanceId,
          });
        });

        continue;
      }

      sendLine(socket, { ok: false, err: "unknown_cmd", cmd, instanceId });
    }
  });

  socket.on("error", () => {});
});

server.on("error", (e) => {
  console.log(`[${nowIso()}] [SERVER ERROR] ${String(e)}`);
});

server.listen(LISTEN_PORT, () => {
  // silent; startup banner already printed
});
