// k6 burst script — POST /api/v1/seats/hold to verify the token-bucket rate limiter.
// Expected outcome: roughly 10 successful holds per user per minute, the rest receive HTTP 429
// with `Retry-After` headers and the `rate-limited` RFC 7807 type.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://nginx';
const TOKEN = __ENV.JWT || ''; // pass a customer access token via --env JWT=...
const EVENT_ID = __ENV.EVENT_ID || '10000000-0000-0000-0000-000000000001';
const SEAT_ID = __ENV.SEAT_ID || '';

const accepted = new Counter('hold_accepted');
const rejected = new Counter('hold_rejected_429');

export const options = {
  vus: 1,
  iterations: 50,
  thresholds: {
    'hold_rejected_429': ['count>30'],
  },
};

export default function () {
  const payload = JSON.stringify({ event_id: EVENT_ID, seat_ids: SEAT_ID ? [SEAT_ID] : [] });
  const res = http.post(`${BASE}/api/v1/seats/hold`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: TOKEN ? `Bearer ${TOKEN}` : '',
    },
  });
  if (res.status === 200) accepted.add(1);
  if (res.status === 429) rejected.add(1);
  check(res, {
    'status 200 or 429': (r) => r.status === 200 || r.status === 429,
  });
}
