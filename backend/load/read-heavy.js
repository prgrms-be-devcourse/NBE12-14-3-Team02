// 읽기 API 부하 테스트 (k6)
// 실행: k6 run load/read-heavy.js
// 옵션: -e BASE_URL=http://localhost:8080 -e USERS=15 -e EXPENSES=50 -e VUS=10 -e DURATION=60s
import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 15); // 모임 인원 (호스트 포함)
const EXPENSES = Number(__ENV.EXPENSES || 50); // 시딩할 지출 수
const PASSWORD = 'Passw0rd!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  stages: [
    { duration: '10s', target: Number(__ENV.VUS || 10) },
    { duration: __ENV.DURATION || '60s', target: Number(__ENV.VUS || 10) },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
  },
};

function post(path, body, token) {
  const headers = token ? { ...JSON_HEADERS, Authorization: `Bearer ${token}` } : JSON_HEADERS;
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers });
}

function get(path, token) {
  return http.get(`${BASE_URL}${path}`, { headers: { Authorization: `Bearer ${token}` } });
}

function must(res, label) {
  if (res.status >= 300) {
    throw new Error(`${label} 실패: ${res.status} ${res.body}`);
  }
  return res.json();
}

// 시딩: 유저 가입/로그인 -> 모임 생성 -> 초대 참여 -> 지출 등록
// 토큰은 여기서 한 번만 발급해 모든 VU가 재사용한다 (BCrypt 로그인 병목 방지).
export function setup() {
  const run = Date.now();
  const tokens = [];

  for (let i = 0; i < USERS; i++) {
    const email = `load${run}_${i}@test.com`;
    must(
      post('/api/user/sign-up', {
        email,
        nickname: `load${i}_${run % 100000}`,
        password: PASSWORD,
        confirmPassword: PASSWORD,
      }),
      `sign-up ${i}`,
    );
    tokens.push(must(post('/api/auth/log-in', { email, password: PASSWORD }), `log-in ${i}`).data.accessToken);
  }

  const host = tokens[0];
  const meeting = must(post('/api/meetings', { name: `load-${run}`, description: 'k6 seed' }, host), 'meeting').data;
  const invite = must(post(`/api/meetings/${meeting.id}/invitations`, {}, host), 'invitation').data;
  for (let i = 1; i < USERS; i++) {
    must(post(`/api/invitations/${invite.inviteCode}/join`, {}, tokens[i]), `join ${i}`);
  }

  const members = must(get(`/api/meetings/${meeting.id}/members`, host), 'members').data;
  const memberIds = members.map((m) => m.id);

  for (let i = 0; i < EXPENSES; i++) {
    must(
      post(
        `/api/meetings/${meeting.id}/expenses`,
        {
          title: `expense-${i}`,
          amount: USERS * (1000 + i * 10), // 인원수의 배수로 맞춰 나머지 차액을 없앤다
          memo: 'k6 seed',
          splitMode: 'EQUAL',
          randomRemainder: false,
          participants: memberIds.map((memberId) => ({ memberId })),
        },
        host,
      ),
      `expense ${i}`,
    );
  }

  console.log(`seeded meeting=${meeting.id} users=${USERS} expenses=${EXPENSES}`);
  return { tokens, meetingId: meeting.id };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const m = `/api/meetings/${data.meetingId}`;

  group('meeting', () => {
    check(get('/api/meetings', token), { 'meeting list 200': (r) => r.status === 200 });
    check(get(m, token), { 'meeting detail 200': (r) => r.status === 200 });
    check(get(`${m}/members`, token), { 'members 200': (r) => r.status === 200 });
  });

  group('expense/settlement', () => {
    check(get(`${m}/expenses`, token), { 'expense list 200': (r) => r.status === 200 });
    check(get(`${m}/settlement/preview`, token), { 'settlement preview 200': (r) => r.status === 200 });
  });

  group('user', () => {
    check(get('/api/user/me', token), { 'me 200': (r) => r.status === 200 });
    check(get('/api/notifications', token), { 'notifications 200': (r) => r.status === 200 });
  });

  sleep(1);
}
