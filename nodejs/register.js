// KloudMate OpenTelemetry for Node.js: NODE_OPTIONS="--require <dir>/register.js".
//
// This file is the startup check. It is written in syntax every Node.js release parses, so a runtime the
// distribution doesn't support still gets a status file saying why, and the app always starts. Only a
// supported runtime loads the SDK (src/start.js).
'use strict';

var MIN = [[18, 19], [20, 6]]; // ^18.19.0 || >=20.6.0, the upstream SDK's engines

function supported(v) {
  var p = String(v).split('.');
  var major = parseInt(p[0], 10);
  var minor = parseInt(p[1], 10);
  if (major === 18) return minor >= MIN[0][1];
  if (major === 20) return minor >= MIN[1][1];
  return major > 20;
}

try {
  if (String(process.env.OTEL_SDK_DISABLED || '').trim().toLowerCase() === 'true') {
    require('./src/status').writeEarly('disabled', 'sdk_disabled');
  } else if (!supported(process.versions.node)) {
    require('./src/status').writeEarly('unsupported_runtime', 'node_too_old');
  } else if (!global.__kloudmateOtelStarted) {
    global.__kloudmateOtelStarted = true;
    require('./src/start').start();
  }
} catch (e) {
  try {
    require('./src/status').writeEarly('error', 'start_failed:' + (e && e.name ? e.name : 'Error'));
  } catch (e2) {
    // nothing left to do; the app runs uninstrumented
  }
}
