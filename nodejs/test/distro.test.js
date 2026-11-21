'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { defaults, settingsFromBlock } = require('../src/start');
const { endpoint, resourceAttrFromEnv } = require('../src/settings');
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
