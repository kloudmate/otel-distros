// KloudMate OpenTelemetry for Node.js, ESM entry: NODE_OPTIONS="--import <dir>/register.mjs".
// Registers the upstream ESM loader hook, then runs the same startup as register.js.
import { createRequire, register } from 'node:module';

const require = createRequire(import.meta.url);
try {
  if (typeof register === 'function') {
    register('@opentelemetry/instrumentation/hook.mjs', import.meta.url);
  }
} catch {
  // ESM hooks unavailable: CommonJS instrumentation still works
}
require('./register.js');
