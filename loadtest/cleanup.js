// Removes what the load test wrote: each demo user deletes their own comments on the c/loadtest thread
// (comment deletion is a soft delete, so the thread keeps "[deleted]" placeholders) and clears their votes there.
// The c/loadtest community and its one thread are kept so the next run reuses them; a platform admin can ban or
// delete the community from the admin dashboard if it should go entirely.
// Run: SCRIPT=cleanup.js sh loadtest/run.sh
import http from 'k6/http';

const BASE = (__ENV.BASE_URL || 'http://host.docker.internal:8088').replace(/\/$/, '') + '/api/v1';
const PASSWORD = __ENV.DEMO_PASSWORD || 'howlers-demo-1';
const USERS = ['mira_k', 'dev_ostrander', 'nadia_sec', 'paperback_jo', 'tunde_builds'];

export const options = { vus: 1, iterations: 1 };

export default function () {
  const posts = http.get(`${BASE}/communities/loadtest/posts?sort=new`);
  if (posts.status !== 200) {
    console.log(`c/loadtest not found (${posts.status}); nothing to clean up`);
    return;
  }
  const postIds = posts.json('items').map((p) => p.id);
  for (const u of USERS) {
    const login = http.post(`${BASE}/auth/login`, JSON.stringify({ login: u, password: PASSWORD }), { headers: { 'Content-Type': 'application/json' } });
    if (login.status !== 200) {
      console.warn(`login ${u} -> ${login.status}`);
      continue;
    }
    const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${login.json('accessToken')}` };
    let deleted = 0;
    for (const postId of postIds) {
      http.post(`${BASE}/posts/${postId}/vote`, JSON.stringify({ value: 0 }), { headers });
      let cursor = '';
      do {
        const page = http.get(`${BASE}/posts/${postId}/comments?sort=new&limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { headers });
        if (page.status !== 200) break;
        for (const c of page.json('items')) {
          if (c.author && c.author.username === u && !c.deleted) {
            if (http.del(`${BASE}/comments/${c.id}`, null, { headers }).status < 300) deleted++;
          }
        }
        cursor = page.json('nextCursor') || '';
      } while (cursor);
    }
    console.log(`${u}: deleted ${deleted} load-test comment(s)`);
  }
}
