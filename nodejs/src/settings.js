'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULT_ENDPOINT = 'http://127.0.0.1:4318';
const PROFILES = new Set(['standard', 'extended', 'off']);

const str = (v) => (v === undefined || v === null || String(v).trim() === '' ? undefined : String(v).trim());
const obj = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : {});

/** The distribution.kloudmate block (specification §1), with the environment as fallback. Unknown keys are ignored. */
function settingsFromBlock(block) {
  const b = obj(block);
  const agent = obj(b.agent);
  let profile = str(obj(b.runtime_metrics).profile) || 'standard';
  if (!PROFILES.has(profile)) profile = 'standard';
  return {
    serviceId: str(b.service_id) || str(process.env.KM_SERVICE_ID),
    otlpSocket: str(agent.otlp_socket) || str(process.env.KM_OTLP_SOCKET),
    otlpEndpoint: str(agent.otlp_endpoint),
    runtimeMetricsProfile: profile,
    statusDir: str(b.status_dir) || str(process.env.KM_SDK_STATUS_DIR) || '/run/kmagent/sdk-status',
  };
}

/**
 * Local-agent discovery over TCP: the block's otlp_endpoint, else 127.0.0.1:4318 (not localhost, which resolves
 * to ::1 first on many hosts). Used when the agent's socket isn't (socketTarget).
 */
function endpoint(s) {
  return (s.otlpEndpoint || DEFAULT_ENDPOINT).replace(/\/+$/, '');
}

// OTLP variables that choose a transport: one set by the app leaves the socket unused.
const TRANSPORT_KEYS = ['', 'TRACES_', 'METRICS_', 'LOGS_'].flatMap((sig) => [
  `OTEL_EXPORTER_OTLP_${sig}ENDPOINT`,
  `OTEL_EXPORTER_OTLP_${sig}PROTOCOL`,
]);

function isSocket(p) {
  try {
    return fs.statSync(p).isSocket();
  } catch {
    return false;
  }
}

/**
 * The agent's OTLP socket as a gRPC target (unix:///path), when it is used: a socket path is configured
 * (distribution.kloudmate.agent.otlp_socket, else KM_OTLP_SOCKET), it is an absolute path to a socket that
 * exists, and the app chose no OTLP endpoint or protocol itself. The agent's socket speaks OTLP/gRPC.
 */
function socketTarget(s, env = process.env) {
  const p = s.otlpSocket;
  if (!p || !path.isAbsolute(p) || TRANSPORT_KEYS.some((k) => env[k])) return undefined;
  return isSocket(p) ? `unix://${p}` : undefined;
}

/** km.service_id from OTEL_RESOURCE_ATTRIBUTES, if the app set it there. */
function resourceAttrFromEnv(key) {
  for (const item of String(process.env.OTEL_RESOURCE_ATTRIBUTES || '').split(',')) {
    const i = item.indexOf('=');
    if (i > 0 && item.slice(0, i).trim() === key) return decodeURIComponent(item.slice(i + 1).trim());
  }
  return undefined;
}

module.exports = { settingsFromBlock, endpoint, socketTarget, resourceAttrFromEnv, DEFAULT_ENDPOINT };
