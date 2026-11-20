// ESM smoke app: same as app.cjs.
import http from 'node:http';
const target = process.env.SMOKE_TARGET || 'http://127.0.0.1:4318/health';
http.get(target, (res) => res.resume()).on('error', () => {});
setTimeout(() => {}, Number(process.env.SMOKE_SLEEP_MS || 3000));
