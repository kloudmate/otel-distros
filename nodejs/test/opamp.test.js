'use strict';
const test = require('node:test');
const assert = require('node:assert');
const os = require('os');
const { AlwaysOnSampler, SamplingDecision } = require('@opentelemetry/sdk-trace-base');
const op = require('../src/opamp');
const { Status } = require('../src/status');

function serverReply(remote, hash = 'h1', flags = 0) {
  const parts = [];
  if (remote !== null) {
    const cfg = op.configMap({ kloudmate: [Buffer.from(JSON.stringify(remote)), 'application/json'] });
    parts.push(op.bytes(3, Buffer.concat([op.bytes(1, cfg), op.bytes(2, Buffer.from(hash))])));
  }
  parts.push(op.uint(6, flags));
  return Buffer.concat(parts);
}

function client(env = {}) {
  const st = new Status();
  st.serviceId = 'systemd:web.service';
  const s = new op.RemoteSampler(new AlwaysOnSampler(), require('@opentelemetry/sdk-trace-base'));
  const c = new op.Client({ endpoint: 'http://x/v1/opamp', status: st, statusDir: os.tmpdir() + '/nonexistent-km', sampler: s, serviceName: 'web', post: async () => Buffer.alloc(0), env });
  return { c, st, s };
}
const { ROOT_CONTEXT } = require('@opentelemetry/api');
const decision = (s) => s.shouldSample(ROOT_CONTEXT, '7fffffffffffffff0000000000000000', 'x').decision;

test('opamp: message fields', async () => {
  const { c } = client();
  const f = op.decode(c.message());
  assert.ok(f.get(1)[0].equals(c.uid) && c.uid.length === 16 && c.uid[6] >> 4 === 7);
  assert.strictEqual(Number(f.get(4)[0]), op.CAPABILITIES);
  const ident = {};
  for (const kv of op.decode(f.get(3)[0]).get(1)) {
    const k = op.decode(kv);
    ident[k.get(1)[0].toString()] = op.decode(k.get(2)[0]).get(1)[0].toString();
  }
  assert.deepStrictEqual([ident['service.name'], ident['km.service_id']], ['web', 'systemd:web.service']);
  const health = op.decode(f.get(5)[0]);
  assert.deepStrictEqual([Number(health.get(1)[0]), health.get(4)[0].toString()], [1, 'ok']);
  const entry = op.decode(op.decode(op.decode(f.get(6)[0]).get(1)[0]).get(1)[0]);
  assert.strictEqual(entry.get(1)[0].toString(), 'kloudmate.status');
  assert.strictEqual(JSON.parse(op.decode(entry.get(2)[0]).get(1)[0].toString())['km.service_id'], 'systemd:web.service');
  await c.pollOnce();
  assert.ok(!op.decode(c.message()).has(3), 'agent_description only until the server has it');
});

test('opamp: remote settings applied live', () => {
  const { c, st, s } = client();
  assert.strictEqual(decision(s), SamplingDecision.RECORD_AND_SAMPLED);
  c.handle(serverReply({ sampling_rate: 0, logging_level: 'debug', future_key: 1 }));
  assert.strictEqual(decision(s), SamplingDecision.NOT_RECORD);
  assert.deepStrictEqual(st.effective.sampling_rate, { value: '0', origin: 'remote' });
  assert.deepStrictEqual([c.remoteStatus, c.remoteError], [op.APPLIED, 'not applied live: logging_level']);
  const rcs = op.decode(op.decode(c.message()).get(7)[0]);
  assert.deepStrictEqual([rcs.get(1)[0].toString(), Number(rcs.get(2)[0])], ['h1', op.APPLIED]);
  c.handle(serverReply({}, 'h2'));
  assert.strictEqual(decision(s), SamplingDecision.RECORD_AND_SAMPLED);
  assert.ok(!('sampling_rate' in st.effective));
  c.handle(serverReply({ send_traces: false }, 'h3'));
  assert.strictEqual(decision(s), SamplingDecision.NOT_RECORD);
});

test('opamp: same hash not reapplied; invalid config fails and changes nothing', () => {
  const { c, s } = client();
  c.handle(serverReply({ send_traces: false }));
  s.set(undefined, true);
  c.handle(serverReply({ send_traces: false }));
  assert.strictEqual(decision(s), SamplingDecision.RECORD_AND_SAMPLED);
  for (const [bad, msg] of [[{ sampling_rate: 2 }, 'sampling_rate'], [{ sampling_rate: true }, 'sampling_rate'], [{ send_traces: 'no' }, 'send_traces'], [[1, 2], 'JSON object']]) {
    c.remoteHash = Buffer.alloc(0);
    c.handle(serverReply(bad));
    assert.strictEqual(c.remoteStatus, op.FAILED);
    assert.ok(c.remoteError.includes(msg), c.remoteError);
    assert.strictEqual(decision(s), SamplingDecision.RECORD_AND_SAMPLED);
  }
});

test('opamp: the app\'s own settings win; full state on request', () => {
  const { c, st, s } = client({ OTEL_TRACES_SAMPLER: 'always_on' });
  c.handle(serverReply({ sampling_rate: 0, send_traces: true }));
  assert.strictEqual(decision(s), SamplingDecision.RECORD_AND_SAMPLED);
  assert.deepStrictEqual(st.overriddenByApp || st.overridden_by_app || st.toJSON().overridden_by_app, ['sampling_rate']);
  assert.strictEqual(c.remoteError, 'set by the application: sampling_rate');
  c.fullState = false;
  c.handle(serverReply(null, 'x', op.REPORT_FULL_STATE));
  assert.ok(op.decode(c.message()).has(3));
});

test('opamp: decoder rejects garbage', () => {
  for (const bad of [Buffer.from([0x0a, 0x05, 0x61]), Buffer.alloc(11, 0xff), Buffer.from([0x0f])]) {
    assert.throws(() => op.decode(bad));
  }
});
