'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const net = require('net');

const { defaults, fillGrpcSocket, settingsFromBlock } = require('../src/start');
const { endpoint, resourceAttrFromEnv, socketTarget } = require('../src/settings');
const { Status } = require('../src/status');

function withEnv(env, fn) {
  const saved = { ...process.env };
  for (const k of Object.keys(process.env)) if (k.startsWith('OTEL_') || k.startsWith('KM_')) delete process.env[k];
  Object.assign(process.env, env);
  try {
    return fn();
  } finally {
    process.env = saved;
  }
}

test('settings: defaults, block, unknown keys', () => withEnv({}, () => {
  const s = settingsFromBlock(undefined);
  assert.strictEqual(endpoint(s), 'http://127.0.0.1:4318');
  assert.strictEqual(s.runtimeMetricsProfile, 'standard');
  const b = settingsFromBlock({ service_id: 'docker:web', agent: { otlp_endpoint: 'http://agent:4318/' }, runtime_metrics: { profile: 'bogus' }, future: 1 });
  assert.deepStrictEqual([b.serviceId, endpoint(b), b.runtimeMetricsProfile], ['docker:web', 'http://agent:4318', 'standard']);
}));

test('defaults: router off unless the app sets an instrumentation list', () => withEnv({}, () => {
  assert.strictEqual(defaults(settingsFromBlock(undefined), false).OTEL_NODE_DISABLED_INSTRUMENTATIONS, 'router');
  assert.strictEqual(defaults(settingsFromBlock({ runtime_metrics: { profile: 'off' } }), false).OTEL_NODE_DISABLED_INSTRUMENTATIONS, 'router,runtime-node');
  process.env.OTEL_NODE_ENABLED_INSTRUMENTATIONS = 'http';
  assert.strictEqual(defaults(settingsFromBlock(undefined), false).OTEL_NODE_DISABLED_INSTRUMENTATIONS, undefined);
}));

test('defaults: file mode leaves the general endpoint alone', () => withEnv({}, () => {
  assert.strictEqual(defaults(settingsFromBlock(undefined), true).OTEL_EXPORTER_OTLP_ENDPOINT, undefined);
}));

test('km.service_id from OTEL_RESOURCE_ATTRIBUTES', () => withEnv({ OTEL_RESOURCE_ATTRIBUTES: 'a=b,km.service_id=app%2Down' }, () => {
  assert.strictEqual(resourceAttrFromEnv('km.service_id'), 'app-own');
  assert.strictEqual(resourceAttrFromEnv('missing'), undefined);
}));

test('status: worst result wins, only into an existing dir', () => {
  const st = new Status();
  st.fail('degraded', 'foreign_agent:ddtrace');
  st.fail('ok', 'ignored');
  assert.strictEqual(st.write(path.join(os.tmpdir(), 'km-missing-dir-xyz')), null);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'km-'));
  const doc = JSON.parse(fs.readFileSync(st.write(dir), 'utf8'));
  assert.deepStrictEqual([doc.result, doc.reason, doc.distro.name], ['degraded', 'foreign_agent:ddtrace', 'kloudmate-nodejs']);
});

test('status: overridden_by_app from file substitutions, sorted', () => {
  const { appSubstitutions } = require('../src/status');
  const text = 'value: ${OTEL_SERVICE_NAME:-orders}\nx: ${env:OTEL_EXPORTER_OTLP_ENDPOINT:-http://127.0.0.1:4318}\n' +
    'y: ${OTEL_TRACES_SAMPLER_ARG:-1.0}\nz: ${OTEL_CONFIG_FILE}\nw: ${OTEL_SERVICE_NAME}\n';
  const env = { OTEL_SERVICE_NAME: 'checkout', OTEL_EXPORTER_OTLP_ENDPOINT: ' ', OTEL_CONFIG_FILE: '/x' };
  assert.deepStrictEqual(appSubstitutions(text, env), ['OTEL_SERVICE_NAME']);
  assert.deepStrictEqual(appSubstitutions(null, env), []);
  const st = new Status();
  st.overridden('OTEL_SERVICE_NAME');
  st.overridden('OTEL_EXPORTER_OTLP_ENDPOINT');
  st.overridden('OTEL_SERVICE_NAME');
  assert.deepStrictEqual(st.toJSON().overridden_by_app, ['OTEL_EXPORTER_OTLP_ENDPOINT', 'OTEL_SERVICE_NAME']);
});

test('file mode: resource attributes added to the model, km.service_id never rewritten', () => {
  const { addResourceAttributes } = require('../src/start');
  const m = { resource: { attributes: [{ name: 'service.name', value: 'web' }, { name: 'km.service_id', value: 'own' },
    { name: 'telemetry.distro.name', value: 'opentelemetry-js' }] } };
  addResourceAttributes(m, { 'km.service_id': 'systemd:x', 'telemetry.distro.name': 'kloudmate-nodejs', 'telemetry.distro.version': '0.2.0' });
  const byName = Object.fromEntries(m.resource.attributes.map((a) => [a.name, a.value]));
  assert.deepStrictEqual(byName, { 'service.name': 'web', 'km.service_id': 'own', 'telemetry.distro.name': 'kloudmate-nodejs', 'telemetry.distro.version': '0.2.0' });
  assert.deepStrictEqual(addResourceAttributes({}, { a: '1' }).resource.attributes, [{ name: 'a', value: '1', type: 'string' }]);
});

async function withSocket(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'km-sock-'));
  const sock = path.join(dir, 'otlp.sock');
  const srv = net.createServer().listen(sock);
  await new Promise((r) => srv.once('listening', r));
  try {
    return fn(sock, dir);
  } finally {
    srv.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('socket: used when it exists and the app chose no transport', () => withSocket((sock, dir) => {
  withEnv({ KM_OTLP_SOCKET: sock }, () => {
    const d = defaults(settingsFromBlock(undefined), false);
    assert.deepStrictEqual(
      [d.OTEL_EXPORTER_OTLP_PROTOCOL, d.OTEL_EXPORTER_OTLP_ENDPOINT, d.OTEL_EXPORTER_OTLP_INSECURE],
      ['grpc', `unix://${sock}`, 'true'],
    );
  });
  // the block's path wins over KM_OTLP_SOCKET
  withEnv({ KM_OTLP_SOCKET: '/nowhere.sock' }, () => {
    assert.strictEqual(socketTarget(settingsFromBlock({ agent: { otlp_socket: sock } })), `unix://${sock}`);
  });
  for (const k of ['OTEL_EXPORTER_OTLP_ENDPOINT', 'OTEL_EXPORTER_OTLP_PROTOCOL', 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT', 'OTEL_EXPORTER_OTLP_LOGS_PROTOCOL']) {
    withEnv({ KM_OTLP_SOCKET: sock, [k]: 'x' }, () => assert.strictEqual(socketTarget(settingsFromBlock(undefined)), undefined, k));
  }
  const file = path.join(dir, 'plain');
  fs.writeFileSync(file, '');
  for (const p of [file, path.join(dir, 'missing.sock'), 'relative.sock']) {
    withEnv({ KM_OTLP_SOCKET: p }, () => {
      assert.strictEqual(socketTarget(settingsFromBlock(undefined)), undefined, p);
      const d = defaults(settingsFromBlock(undefined), false);
      assert.deepStrictEqual([d.OTEL_EXPORTER_OTLP_PROTOCOL, d.OTEL_EXPORTER_OTLP_ENDPOINT, d.OTEL_EXPORTER_OTLP_INSECURE], ['http/protobuf', 'http://127.0.0.1:4318', undefined]);
    });
  }
  // file mode: the file names its exporters
  withEnv({ KM_OTLP_SOCKET: sock }, () => assert.strictEqual(defaults(settingsFromBlock(undefined), true).OTEL_EXPORTER_OTLP_PROTOCOL, 'http/protobuf'));
}));

test('file mode: otlp_grpc exporters without an endpoint get the socket', () => {
  const model = {
    tracer_provider: { processors: [{ batch: { exporter: { otlp_grpc: null } } }, { simple: { exporter: { otlp_http: {} } } }] },
    meter_provider: { readers: [{ periodic: { exporter: { otlp_grpc: { endpoint: 'http://collector:4317' } } } }] },
    logger_provider: { processors: [{ batch: { exporter: { otlp_grpc: { tls: { ca_file: '/ca.pem' } } } } }] },
  };
  assert.deepStrictEqual(fillGrpcSocket(model, 'unix:///run/otlp.sock'), ['TRACES', 'LOGS']);
  assert.deepStrictEqual(model.tracer_provider.processors[0].batch.exporter.otlp_grpc, { endpoint: 'unix:///run/otlp.sock', tls: { insecure: true } });
  assert.deepStrictEqual(model.tracer_provider.processors[1].simple.exporter, { otlp_http: {} });
  assert.strictEqual(model.meter_provider.readers[0].periodic.exporter.otlp_grpc.endpoint, 'http://collector:4317');
  assert.deepStrictEqual(model.logger_provider.processors[0].batch.exporter.otlp_grpc.tls, { ca_file: '/ca.pem' });
  assert.deepStrictEqual(fillGrpcSocket({}, 'unix:///x'), []);
});
