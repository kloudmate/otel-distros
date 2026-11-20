// Startup status (specification §5): <status_dir>/<pid>.json, only into an existing directory,
// replaced atomically, never throwing. ES5 syntax: the startup check uses it on old runtimes too.
'use strict';

var fs = require('fs');
var path = require('path');

var NAME = 'kloudmate-nodejs';
var VERSION = require('../package.json').version;
var ORDER = { ok: 0, degraded: 1, unsupported_runtime: 2, denied: 2, disabled: 2, error: 3 };

function statusDir(block) {
  return (block && block.status_dir) || process.env.KM_SDK_STATUS_DIR || '/run/kmagent/sdk-status';
}

function Status() {
  this.serviceId = '';
  this.result = 'ok';
  this.reason = '';
  this.configHash = '';
  this.effective = {};
  this.overriddenByApp = [];
  this.instrumentations = [];
}

Status.prototype.put = function (key, value, origin) {
  if (value !== undefined && value !== null) this.effective[key] = { value: String(value), origin: origin };
};

Status.prototype.fail = function (result, reason) {
  var cur = ORDER[this.result] === undefined ? 0 : ORDER[this.result];
  var next = ORDER[result] === undefined ? 3 : ORDER[result];
  if (next > cur) {
    this.result = result;
    this.reason = reason;
  }
};

Status.prototype.toJSON = function () {
  var now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
  return {
    schema_version: 1,
    pid: process.pid,
    'km.service_id': this.serviceId || '',
    distro: { name: NAME, version: VERSION },
    result: this.result,
    reason: this.reason,
    started_at: now,
    heartbeat_at: now,
    config_hash: this.configHash,
    effective_config: this.effective,
    overridden_by_app: this.overriddenByApp,
    instrumentations: this.instrumentations,
    runtime: { name: 'nodejs', version: process.versions.node }
  };
};

Status.prototype.write = function (dir) {
  try {
    if (!fs.statSync(dir).isDirectory()) return null;
    var file = path.join(dir, process.pid + '.json');
    var tmp = path.join(dir, '.' + process.pid + '.json.tmp');
    fs.writeFileSync(tmp, JSON.stringify(this.toJSON()) + '\n');
    fs.renameSync(tmp, file);
    return file;
  } catch (e) {
    return null;
  }
};

function writeEarly(result, reason) {
  var st = new Status();
  st.serviceId = process.env.KM_SERVICE_ID || '';
  st.fail(result, reason);
  return st.write(statusDir(null));
}

module.exports = { Status: Status, writeEarly: writeEarly, statusDir: statusDir, NAME: NAME, VERSION: VERSION };
