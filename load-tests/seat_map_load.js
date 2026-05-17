// k6 load script — GET /api/v1/events/{id}/seats (Spec §6.3).
// Acceptance: p95 must improve >= 5x once the Redis read-through cache is warm.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx';
const EVENT_ID = __ENV.EVENT_ID || '10000000-0000-0000-0000-000000000001';
const ttfb = new Trend('seat_map_ttfb_ms');

export const options = {
  vus: 100,
  duration: '60s',
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<400'],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/v1/events/${EVENT_ID}/seats`, {
    headers: { 'X-Request-Id': `k6-${__VU}-${__ITER}` },
  });
  ttfb.add(res.timings.waiting);
  check(res, {
    'status 200': (r) => r.status === 200,
    'has rows or seats': (r) => /\b(rows|seats)\b/.test(r.body || ''),
  });
  sleep(0.1);
}
