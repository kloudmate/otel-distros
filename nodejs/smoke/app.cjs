// Smoke app: one HTTP client call (a span), then stays up for a metric export.
const http = require('http');
const target = process.env.SMOKE_TARGET || 'http://127.0.0.1:4318/health';
const wait = Number(process.env.SMOKE_SLEEP_MS || 3000);
http.get(target, (res) => res.resume()).on('error', () => {});
setTimeout(() => {}, wait);
