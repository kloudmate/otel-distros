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

// A KloudMate-managed setting the app replaced with its own value (sorted, no duplicates).
Status.prototype.overridden = function (key) {
  if (key && this.overriddenByApp.indexOf(key) < 0) {
    this.overriddenByApp.push(key);
    this.overriddenByApp.sort();
  }
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

// The ${OTEL_*} substitutions of a config file that the app supplies itself (set in env), in file order: the
// file's KloudMate values the app replaced.
function appSubstitutions(text, env) {
  var out = [];
  if (!text || !env) return out;
  var re = /\$\{(?:env:)?(OTEL_[A-Z0-9_]+)(?::-[^}]*)?\}/g;
  var m;
  while ((m = re.exec(text)) !== null) {
    var name = m[1];
    var v = env[name];
    if (name !== 'OTEL_CONFIG_FILE' && v !== undefined && String(v).trim() !== '' && out.indexOf(name) < 0) out.push(name);
  }
  return out;
}

function fileText(p) {
  try {
    if (fs.statSync(p).size > 1024 * 1024) return null;
    return fs.readFileSync(p, 'utf8');
  } catch (e) {
    return null;
  }
}

function writeEarly(result, reason) {
  var st = new Status();
  st.serviceId = process.env.KM_SERVICE_ID || '';
  st.fail(result, reason);
  return st.write(statusDir(null));
}

module.exports = {
  Status: Status,
  writeEarly: writeEarly,
  statusDir: statusDir,
  appSubstitutions: appSubstitutions,
  fileText: fileText,
  NAME: NAME,
  VERSION: VERSION
};
