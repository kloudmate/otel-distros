'use strict';
// OpAMP client (specification §4): reports health and the startup status to the local KloudMate Agent over OpAMP's
// plain-HTTP transport, and applies the remote settings that can change live (sampling_rate, send_traces) through
// a sampler wrapped around the configured one. Off unless an OpAMP endpoint is configured. No dependencies beyond
// Node.js and OpenTelemetry: the handful of OpAMP messages it needs are encoded by hand. Never throws into the app.

const crypto = require('crypto');
const http = require('http');
const https = require('https');
const { NAME, VERSION } = require('./status');

const DEFAULT_POLL_SECONDS = 30;
// ReportsStatus | AcceptsRemoteConfig | ReportsEffectiveConfig | ReportsHealth | ReportsRemoteConfig
const CAPABILITIES = 0x1 | 0x2 | 0x4 | 0x800 | 0x1000;
const APPLIED = 1;
const FAILED = 3;
const REPORT_FULL_STATE = 0x1;
const LIVE = ['sampling_rate', 'send_traces'];
const NOT_LIVE = ['logging_level', 'send_metrics', 'send_logs', 'deactivate_instrumentations', 'deactivate_all_instrumentations', 'metrics_profile'];
const APP_KEYS = { sampling_rate: ['OTEL_TRACES_SAMPLER', 'OTEL_TRACES_SAMPLER_ARG'], send_traces: ['OTEL_TRACES_EXPORTER'] };

// --- a minimal protobuf codec ---------------------------------------------------------------------------------------

function varint(n) {
  let v = BigInt(n);
  const out = [];
  do {
    let b = Number(v & 0x7fn);
    v >>= 7n;
    if (v) b |= 0x80;
    out.push(b);
  } while (v);
  return Buffer.from(out);
}
const tag = (num, wire) => varint((num << 3) | wire);
const uint = (num, v) => (v ? Buffer.concat([tag(num, 0), varint(v)]) : Buffer.alloc(0));
const bool = (num, v) => (v ? Buffer.concat([tag(num, 0), Buffer.from([1])]) : Buffer.alloc(0));
function fixed64(num, v) {
  if (!v) return Buffer.alloc(0);
  const b = Buffer.alloc(8);
  b.writeBigUInt64LE(BigInt(v));
  return Buffer.concat([tag(num, 1), b]);
}
const bytes = (num, v) => Buffer.concat([tag(num, 2), varint(v.length), v]);
const str = (num, v) => (v ? bytes(num, Buffer.from(String(v), 'utf8')) : Buffer.alloc(0));

function readVarint(buf, i) {
  let n = 0n;
  let shift = 0n;
  for (;;) {
    if (i >= buf.length || shift > 63n) throw new Error('bad varint');
    const b = buf[i++];
    n |= BigInt(b & 0x7f) << shift;
    if (!(b & 0x80)) return [n, i];
    shift += 7n;
  }
}

/** Fields by number: BigInt for varint/fixed, Buffer for length-delimited. Throws on malformed input. */
function decode(buf) {
  const out = new Map();
  let i = 0;
  while (i < buf.length) {
    let key;
    [key, i] = readVarint(buf, i);
    const num = Number(key >> 3n);
    const wire = Number(key & 7n);
    let v;
    if (wire === 0) [v, i] = readVarint(buf, i);
    else if (wire === 1) {
      if (i + 8 > buf.length) throw new Error('truncated field');
      v = buf.readBigUInt64LE(i);
      i += 8;
    } else if (wire === 2) {
      let n;
      [n, i] = readVarint(buf, i);
      n = Number(n);
      if (i + n > buf.length) throw new Error('truncated field');
      v = buf.subarray(i, i + n);
      i += n;
    } else if (wire === 5) {
      if (i + 4 > buf.length) throw new Error('truncated field');
      v = BigInt(buf.readUInt32LE(i));
      i += 4;
    } else throw new Error(`unsupported wire type ${wire}`);
    if (!out.has(num)) out.set(num, []);
    out.get(num).push(v);
  }
  return out;
}
const last = (m, n, dflt) => (m.has(n) ? m.get(n)[m.get(n).length - 1] : dflt);

function kv(key, value) {
  const any = typeof value === 'number' ? Buffer.concat([tag(3, 0), varint(value)]) : str(1, value);
  return Buffer.concat([str(1, key), bytes(2, any)]);
}

/** AgentConfigMap: map<string, AgentConfigFile{body, content_type}>. */
function configMap(entries) {
  return Buffer.concat(Object.entries(entries).map(([k, [body, ctype]]) =>
    bytes(1, Buffer.concat([str(1, k), bytes(2, Buffer.concat([bytes(1, body), str(2, ctype)]))]))));
}

function uuid7() {
  const b = crypto.randomBytes(16);
  b.writeUIntBE(Date.now(), 0, 6);
  b[6] = 0x70 | (b[6] & 0x0f);
  b[8] = 0x80 | (b[8] & 0x3f);
  return b;
}
const uuidString = (b) => {
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
};

// --- remote settings ------------------------------------------------------------------------------------------------

/** The configured sampler until a remote setting says otherwise. */
class RemoteSampler {
  constructor(delegate, base) {
    this.delegate = delegate;
    this.base = base; // @opentelemetry/sdk-trace-base, for the samplers
    this.override = undefined;
  }

  set(rate, send) {
    const b = this.base;
    if (!send) this.override = new b.AlwaysOffSampler();
    else if (rate !== undefined) this.override = new b.ParentBasedSampler({ root: new b.TraceIdRatioBasedSampler(rate) });
    else this.override = undefined;
  }

  shouldSample(...args) {
    return (this.override || this.delegate).shouldSample(...args);
  }

  toString() {
    return `KloudMateRemote{${(this.override || this.delegate).toString()}}`;
  }
}

/** The remote settings, validated. Throws an Error whose message goes to the server. */
function parseRemote(body) {
  if (!body || !body.toString('utf8').trim()) return {};
  let data;
  try {
    data = JSON.parse(body.toString('utf8'));
  } catch {
    throw new Error('remote config is not a JSON object');
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('remote config is not a JSON object');
  if ('sampling_rate' in data) {
    const r = data.sampling_rate;
    if (typeof r !== 'number' || !(r >= 0 && r <= 1)) throw new Error('sampling_rate: a number from 0 to 1');
  }
  if ('send_traces' in data && typeof data.send_traces !== 'boolean') throw new Error('send_traces: true or false');
  const out = {};
  for (const [k, v] of Object.entries(data)) if (LIVE.includes(k) || NOT_LIVE.includes(k)) out[k] = v;
  return out;
}

// --- the client -----------------------------------------------------------------------------------------------------

class Client {
  constructor({ endpoint, status, statusDir, sampler, serviceName, interval, post, env }) {
    this.endpoint = endpoint;
    this.status = status;
    this.statusDir = statusDir;
    this.sampler = sampler;
    this.serviceName = serviceName;
    this.interval = interval || DEFAULT_POLL_SECONDS;
    this.post = post || postSuppressed;
    this.env = env || process.env;
    this.uid = uuid7();
    this.seq = 0;
    this.startNs = BigInt(Date.now()) * 1000000n;
    this.fullState = true;
    this.remoteHash = Buffer.alloc(0);
    this.remoteStatus = 0;
    this.remoteError = '';
    this.timer = null;
  }

  message() {
    const st = this.status;
    const healthy = st.result === 'ok' || st.result === 'degraded';
    const health = Buffer.concat([bool(1, healthy), fixed64(2, this.startNs), str(3, st.result === 'ok' ? '' : st.reason), str(4, st.result)]);
    const parts = [bytes(1, this.uid), uint(2, this.seq)];
    if (this.fullState) {
      const ident = [['service.name', this.serviceName], ['service.instance.id', uuidString(this.uid)]];
      if (st.serviceId) ident.push(['km.service_id', st.serviceId]);
      const other = [['telemetry.distro.name', NAME], ['telemetry.distro.version', VERSION], ['process.pid', process.pid], ['os.type', 'linux']];
      parts.push(bytes(3, Buffer.concat([...ident.map(([k, v]) => bytes(1, kv(k, v))), ...other.map(([k, v]) => bytes(2, kv(k, v)))])));
    }
    parts.push(uint(4, CAPABILITIES), bytes(5, health));
    const statusJson = Buffer.from(JSON.stringify(st.toJSON()), 'utf8');
    parts.push(bytes(6, bytes(1, configMap({ 'kloudmate.status': [statusJson, 'application/json'] }))));
    if (this.remoteHash.length) {
      parts.push(bytes(7, Buffer.concat([bytes(1, this.remoteHash), uint(2, this.remoteStatus), str(3, this.remoteError)])));
    }
    return Buffer.concat(parts);
  }

  handle(reply) {
    const f = decode(reply);
    if (Number(last(f, 6, 0n)) & REPORT_FULL_STATE) this.fullState = true;
    for (const rc of f.get(3) || []) {
      const rcf = decode(rc);
      const h = last(rcf, 2, Buffer.alloc(0));
      if (!h.length || h.equals(this.remoteHash)) continue;
      let body;
      for (const cm of rcf.get(1) || []) {
        for (const entry of decode(cm).get(1) || []) {
          const e = decode(entry);
          if (last(e, 1, Buffer.alloc(0)).toString('utf8') === 'kloudmate') body = last(decode(last(e, 2, Buffer.alloc(0))), 1, Buffer.alloc(0));
        }
      }
      this.apply(Buffer.from(h), body);
    }
  }

  apply(hash, body) {
    this.remoteHash = hash;
    let remote;
    try {
      remote = parseRemote(body);
    } catch (e) {
      this.remoteStatus = FAILED;
      this.remoteError = e.message;
      return;
    }
    const overridden = LIVE.filter((k) => k in remote && APP_KEYS[k].some((a) => this.env[a]));
    let notLive = Object.keys(remote).filter((k) => NOT_LIVE.includes(k)).sort();
    let live = {};
    for (const k of LIVE) if (k in remote && !overridden.includes(k)) live[k] = remote[k];
    if (!this.sampler && Object.keys(live).length) {
      notLive = [...notLive, ...Object.keys(live)].sort();
      live = {};
    }
    if (this.sampler) this.sampler.set(live.sampling_rate, live.send_traces !== false);
    const st = this.status;
    for (const k of LIVE) delete st.effective[k];
    for (const [k, v] of Object.entries(live)) st.put(k, JSON.stringify(v), 'remote');
    for (const k of overridden) st.overridden(k);
    const notes = [];
    if (overridden.length) notes.push(`set by the application: ${overridden.join(', ')}`);
    if (notLive.length) notes.push(`not applied live: ${notLive.join(', ')}`);
    this.remoteStatus = APPLIED;
    this.remoteError = notes.join('; ');
    st.write(this.statusDir);
  }

  async pollOnce() {
    const reply = await this.post(this.endpoint, this.message());
    this.seq += 1;
    this.fullState = false;
    this.handle(reply);
  }

  start() {
    const tick = async () => {
      try {
        await this.pollOnce();
      } catch {
        this.fullState = true; // the agent may not be up yet; try again next time
      }
      this.timer = setTimeout(tick, this.interval * 1000 * (0.9 + Math.random() * 0.2));
      this.timer.unref();
    };
    setImmediate(tick).unref();
  }

  stop() {
    if (this.timer) clearTimeout(this.timer);
  }
}

/** POSTs with tracing suppressed: the client's own requests are never traced. */
function postSuppressed(url, body) {
  const api = require('@opentelemetry/api');
  const { suppressTracing } = require('@opentelemetry/core');
  return api.context.with(suppressTracing(api.context.active()), () => new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = (u.protocol === 'https:' ? https : http).request(u, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-protobuf', 'Content-Length': body.length },
      timeout: 10000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => (res.statusCode >= 200 && res.statusCode < 300 ? resolve(Buffer.concat(chunks)) : reject(new Error(`HTTP ${res.statusCode}`))));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(body);
  }));
}

function pollInterval(env = process.env) {
  const v = Number(env.KM_OPAMP_POLL_INTERVAL);
  return Number.isFinite(v) && v > 0 ? Math.max(1, v) : DEFAULT_POLL_SECONDS;
}

/**
 * Wraps the tracer provider's sampler: in the options new tracers copy, and in the tracers that already exist.
 * Returns the wrapper, or undefined when the provider isn't one this release knows.
 */
function installSampler(api) {
  try {
    let provider = api.trace.getTracerProvider();
    if (provider && typeof provider.getDelegate === 'function') provider = provider.getDelegate();
    const opts = provider && provider._tracerOptions;
    if (!opts || !opts.sampler || typeof opts.sampler.shouldSample !== 'function') return undefined;
    const wrapped = new RemoteSampler(opts.sampler, require('@opentelemetry/sdk-trace-base'));
    opts.sampler = wrapped;
    for (const t of (provider._tracers && provider._tracers.values()) || []) if (t && '_sampler' in t) t._sampler = wrapped;
    return wrapped;
  } catch {
    return undefined;
  }
}

/** Starts the client when an endpoint is configured. */
function start({ endpoint, status, statusDir, serviceName }) {
  if (!endpoint) return undefined;
  try {
    const api = require('@opentelemetry/api');
    const client = new Client({ endpoint, status, statusDir, sampler: installSampler(api), serviceName, interval: pollInterval() });
    client.start();
    return client;
  } catch {
    return undefined;
  }
}

module.exports = { Client, RemoteSampler, parseRemote, decode, configMap, bytes, uint, start, installSampler, CAPABILITIES, APPLIED, FAILED, REPORT_FULL_STATE };
