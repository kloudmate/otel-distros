'use strict';
// Starts upstream OpenTelemetry with KloudMate defaults. Env mode (default) mirrors upstream
// auto-instrumentations-node/register on the stable NodeSDK; with OTEL_CONFIG_FILE it uses upstream's
// experimental startNodeSDK. Every step is guarded: a failure leaves the app running.

const fs = require('fs');
const crypto = require('crypto');
const { Status, NAME, VERSION, appSubstitutions, fileText } = require('./status');
const { settingsFromBlock, endpoint, socketTarget, resourceAttrFromEnv } = require('./settings');

const KM_SERVICE_ID = 'km.service_id';

// Env keys the app set itself, recorded before defaults are applied (origin app_env).
const appSet = new Set();

function defaults(settings, fileMode) {
  // Env mode prefers the agent's socket (OTLP/gRPC); a file names its own exporters.
  const sock = fileMode ? undefined : socketTarget(settings);
  const d = {
    OTEL_TRACES_EXPORTER: 'otlp',
    OTEL_METRICS_EXPORTER: 'otlp',
    OTEL_LOGS_EXPORTER: 'otlp',
    OTEL_EXPORTER_OTLP_PROTOCOL: sock ? 'grpc' : 'http/protobuf',
    OTEL_PROPAGATORS: 'tracecontext,baggage',
  };
  if (!fileMode) d.OTEL_EXPORTER_OTLP_ENDPOINT = sock || endpoint(settings);
  if (sock) d.OTEL_EXPORTER_OTLP_INSECURE = 'true'; // no TLS on a local socket
  // Express 5 runs on `router`, which the express instrumentation already traces: router spans would
  // double every middleware. A runtime_metrics profile of off drops the runtime-node instrumentation.
  // Either applies only when the app sets neither instrumentation list.
  if (process.env.OTEL_NODE_DISABLED_INSTRUMENTATIONS === undefined && process.env.OTEL_NODE_ENABLED_INSTRUMENTATIONS === undefined) {
    d.OTEL_NODE_DISABLED_INSTRUMENTATIONS = settings.runtimeMetricsProfile === 'off' ? 'router,runtime-node' : 'router';
  }
  return d;
}

function applyDefaults(d) {
  for (const [k, v] of Object.entries(d)) {
    if (process.env[k] !== undefined && process.env[k] !== '') appSet.add(k);
    else process.env[k] = v;
  }
}

function kmDetector(attrs) {
  return { detect: () => ({ attributes: attrs }) };
}

function foreignAgents(st) {
  const opts = `${process.env.NODE_OPTIONS || ''} ${process.execArgv.join(' ')}`;
  const known = [['dd-trace', 'foreign_agent:ddtrace'], ['newrelic', 'foreign_agent:newrelic'], ['elastic-apm-node', 'foreign_agent:elastic']];
  for (const [mod, reason] of known) if (opts.includes(mod)) st.fail('degraded', reason);
}

function fileHash(p) {
  try {
    return `sha256:${crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')}`;
  } catch {
    return '';
  }
}

/**
 * The global MeterProvider is first-writer-wins: once the SDK sets it, an app that installs its own later is
 * silently ignored. Release it right after start and reclaim it on the next tick unless the app took it during
 * main-module load. Instrumentations keep the meter they already got, so their metrics keep flowing.
 */
function releaseMeterProvider(api, before) {
  try {
    const ours = api.metrics.getMeterProvider();
    if (ours !== before && typeof api.metrics.disable === 'function') {
      api.metrics.disable();
      process.nextTick(() => {
        try {
          api.metrics.setGlobalMeterProvider(ours);
        } catch {
          // the app owns it
        }
      });
    }
  } catch {
    // keep upstream behaviour
  }
}

function startEnv(api) {
  const settings = settingsFromBlock(undefined);
  const d = defaults(settings, false);
  applyDefaults(d);

  const st = new Status();
  for (const k of Object.keys(d)) {
    st.put(k, process.env[k], appSet.has(k) ? 'app_env' : 'distro_default');
    if (appSet.has(k)) st.overridden(k);
  }
  for (const k of ['OTEL_SERVICE_NAME', 'OTEL_TRACES_SAMPLER', 'OTEL_TRACES_SAMPLER_ARG']) {
    if (process.env[k]) st.put(k, process.env[k], 'app_env');
  }
  const envId = resourceAttrFromEnv(KM_SERVICE_ID);
  st.serviceId = envId || settings.serviceId;
  foreignAgents(st);

  const attrs = { 'telemetry.distro.name': NAME, 'telemetry.distro.version': VERSION };
  if (settings.serviceId && envId === undefined) attrs[KM_SERVICE_ID] = settings.serviceId;

  const { NodeSDK } = require('@opentelemetry/sdk-node');
  const { getNodeAutoInstrumentations, getResourceDetectors } = require('@opentelemetry/auto-instrumentations-node');
  const core = require('@opentelemetry/core');
  const logLevel = process.env.OTEL_LOG_LEVEL;
  if (logLevel) api.diag.setLogger(new api.DiagConsoleLogger(), { logLevel: core.diagLogLevelFromString(logLevel) });

  const instrumentations = getNodeAutoInstrumentations();
  st.instrumentations = instrumentations.map((i) => i.instrumentationName.replace('@opentelemetry/instrumentation-', ''));
  st.write(settings.statusDir);

  const before = api.metrics.getMeterProvider();
  const sdk = new NodeSDK({ instrumentations, resourceDetectors: [...getResourceDetectors(), kmDetector(attrs)] });
  sdk.start();
  releaseMeterProvider(api, before);
  return () => sdk.shutdown();
}

const firstHttpEndpoint = (list, pick) => {
  for (const item of list || []) {
    const exp = pick(item);
    if (exp && exp.otlp_http) return exp.otlp_http.endpoint || '';
  }
  return undefined; // no otlp_http exporter for this signal
};

/**
 * Per-signal endpoints for file mode, through the env the upstream exporters fall back to: the file's endpoint
 * when it has one (sdk-node 0.219 drops the metric exporter's endpoint from the file), else the agent endpoint
 * (the fill the other distributions do in the model). A per-signal endpoint the app sets itself wins.
 */
function fileEndpoints(model, settings, st) {
  const base = endpoint(settings);
  const signals = {
    TRACES: ['/v1/traces', firstHttpEndpoint(model.tracer_provider && model.tracer_provider.processors, (p) => (p.batch || p.simple || {}).exporter)],
    METRICS: ['/v1/metrics', firstHttpEndpoint(model.meter_provider && model.meter_provider.readers, (r) => (r.periodic || {}).exporter)],
    LOGS: ['/v1/logs', firstHttpEndpoint(model.logger_provider && model.logger_provider.processors, (p) => (p.batch || p.simple || {}).exporter)],
  };
  for (const [sig, [path, fromFile]] of Object.entries(signals)) {
    if (fromFile === undefined) continue;
    const key = `OTEL_EXPORTER_OTLP_${sig}_ENDPOINT`;
    if (process.env[key]) {
      st.put(key, process.env[key], 'app_env');
      continue;
    }
    process.env[key] = fromFile || base + path;
    st.put(key, process.env[key], fromFile ? 'file' : 'distro_default');
  }
}

/**
 * Adds resource attributes to a config model: telemetry.distro.* replace upstream's, any other key only when the
 * file doesn't set it (km.service_id is never rewritten).
 */
function addResourceAttributes(model, attrs) {
  model.resource = model.resource || {};
  const distro = new Set(['telemetry.distro.name', 'telemetry.distro.version']);
  const list = (Array.isArray(model.resource.attributes) ? model.resource.attributes : []).filter((a) => !(a && distro.has(a.name)));
  for (const [name, value] of Object.entries(attrs)) {
    if (!list.some((a) => a && a.name === name)) list.push({ name, value, type: 'string' });
  }
  model.resource.attributes = list;
  return model;
}

const SIGNAL_EXPORTERS = {
  TRACES: (m) => ((m.tracer_provider && m.tracer_provider.processors) || []).map((p) => (p && (p.batch || p.simple)) || {}),
  METRICS: (m) => ((m.meter_provider && m.meter_provider.readers) || []).map((r) => (r && r.periodic) || {}),
  LOGS: (m) => ((m.logger_provider && m.logger_provider.processors) || []).map((p) => (p && (p.batch || p.simple)) || {}),
};

/**
 * Points every otlp_grpc exporter without an endpoint at the agent's socket (target is unix:///path), without TLS
 * unless the file configures it. Returns the signals it filled.
 */
function fillGrpcSocket(model, target) {
  const filled = [];
  for (const [sig, holders] of Object.entries(SIGNAL_EXPORTERS)) {
    for (const h of holders(model)) {
      const exp = h.exporter;
      if (!exp || typeof exp !== 'object' || !('otlp_grpc' in exp)) continue;
      const g = exp.otlp_grpc && typeof exp.otlp_grpc === 'object' ? exp.otlp_grpc : {};
      if (g.endpoint) continue;
      g.endpoint = target;
      if (!g.tls) g.tls = { insecure: true };
      exp.otlp_grpc = g;
      if (!filled.includes(sig)) filled.push(sig);
    }
  }
  return filled;
}

/** Runs fn while the file config loader hands out models passed through customize; the loader is restored afterwards. */
function withFileModel(customize, fn) {
  let FileConfigFactory;
  try {
    ({ FileConfigFactory } = require('@opentelemetry/configuration/build/src/FileConfigFactory'));
  } catch {
    return fn();
  }
  const original = FileConfigFactory.prototype.getConfigModel;
  FileConfigFactory.prototype.getConfigModel = function () {
    const model = original.call(this);
    try {
      return customize(model);
    } catch {
      return model;
    }
  };
  try {
    return fn();
  } finally {
    FileConfigFactory.prototype.getConfigModel = original;
  }
}

function startFile(api, file) {
  const st = new Status();
  st.configHash = fileHash(file);
  st.put('OTEL_CONFIG_FILE', file, 'app_env');
  // Before the distro sets anything: every OTEL_* in the environment now is the app's.
  for (const name of appSubstitutions(fileText(file), process.env)) {
    st.put(name, process.env[name], 'app_env');
    st.overridden(name);
  }
  let model;
  try {
    model = require('@opentelemetry/configuration').createConfigFactory().getConfigModel();
  } catch (e) {
    st.fail('error', 'config_parse_error');
    st.write(settingsFromBlock(undefined).statusDir);
    return null; // upstream would also leave the SDK off
  }
  const settings = settingsFromBlock(model.distribution && model.distribution.kloudmate);
  const sock = socketTarget(settings); // before the distro sets any OTLP variable
  applyDefaults(defaults(settings, true));
  fileEndpoints(model, settings, st);
  if (sock) {
    for (const sig of fillGrpcSocket(model, sock)) st.put(`OTEL_EXPORTER_OTLP_${sig}_ENDPOINT`, sock, 'distro_default');
  }
  const fileAttrs = (model.resource && model.resource.attributes) || [];
  const fileId = fileAttrs.find((a) => a && a.name === KM_SERVICE_ID);
  st.serviceId = fileId ? String(fileId.value) : settings.serviceId;
  const attrs = { 'telemetry.distro.name': NAME, 'telemetry.distro.version': VERSION };
  if (!fileId && settings.serviceId) attrs[KM_SERVICE_ID] = settings.serviceId;
  if (model.disabled) st.fail('disabled', 'sdk_disabled');
  st.put('runtime_metrics.profile', settings.runtimeMetricsProfile, 'file');
  foreignAgents(st);

  const { startNodeSDK } = require('@opentelemetry/sdk-node');
  const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
  const instrumentations = getNodeAutoInstrumentations();
  st.instrumentations = instrumentations.map((i) => i.instrumentationName.replace('@opentelemetry/instrumentation-', ''));
  st.write(settings.statusDir);

  const before = api.metrics.getMeterProvider();
  // sdk-node 0.223 builds the resource from the file alone (resourceDetectors is ignored): add ours to the model,
  // with the socket fill.
  const customize = (m) => {
    addResourceAttributes(m, attrs);
    if (sock) fillGrpcSocket(m, sock);
    return m;
  };
  const sdk = withFileModel(customize, () => startNodeSDK({ instrumentations }));
  // startNodeSDK (experimental) enables instrumentations before it sets the global MeterProvider, so they keep
  // a no-op meter; NodeSDK hands them the provider explicitly. Do the same.
  const mp = api.metrics.getMeterProvider();
  if (mp !== before) {
    for (const i of instrumentations) {
      try {
        i.setMeterProvider(mp);
      } catch {
        // that instrumentation keeps its meter
      }
    }
  }
  releaseMeterProvider(api, before);
  return () => sdk.shutdown();
}

function start() {
  const api = require('@opentelemetry/api');
  let shutdown = null;
  try {
    const file = process.env.OTEL_CONFIG_FILE;
    shutdown = file ? startFile(api, file) : startEnv(api);
  } catch (e) {
    const st = new Status();
    st.fail('error', `start_failed:${(e && e.name) || 'Error'}`);
    st.write(settingsFromBlock(undefined).statusDir);
    api.diag.error('KloudMate OpenTelemetry not started; the application runs uninstrumented', e);
    return;
  }
  if (!shutdown) return;
  const stop = async () => {
    try {
      await shutdown();
    } catch {
      // nothing to report at exit
    }
  };
  process.once('beforeExit', stop);
  // Flush on SIGTERM without changing what SIGTERM does. Upstream's register adds a plain listener, which
  // stops an app with no handler of its own from exiting; here, when ours is the only handler, the signal
  // is re-raised after the flush so the default exit still happens.
  const onTerm = async () => {
    const alone = process.listenerCount('SIGTERM') === 1;
    await stop();
    if (alone) {
      process.removeListener('SIGTERM', onTerm);
      process.kill(process.pid, 'SIGTERM');
    }
  };
  process.on('SIGTERM', onTerm);
}

module.exports = { addResourceAttributes, fillGrpcSocket, start, defaults, settingsFromBlock };
