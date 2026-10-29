import { writeFileSync } from 'node:fs';

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const tspans = (x, lines, lh) => lines.map((l, i) => `<tspan x="${x}" dy="${i ? lh : 0}">${esc(l)}</tspan>`).join('');
const txt = (x, y, lines, cls = '', lh = 15, anchor = 'middle') =>
  `<text x="${x}" y="${y}" class="${cls}" text-anchor="${anchor}">${tspans(x, [].concat(lines), lh)}</text>`;
// label whose last line sits on baseline y (stacks upward)
const labelAbove = (x, y, lines, anchor = 'middle') => {
  lines = [].concat(lines);
  return txt(x, y - (lines.length - 1) * 15, lines, 'lbl', 15, anchor);
};
const arrow = (pts, { dashed = false, both = false } = {}) =>
  `<polyline class="arr${dashed ? ' dash' : ''}" points="${pts.map(p => p.join(',')).join(' ')}" marker-end="url(#ah)"${both ? ' marker-start="url(#ah)"' : ''}/>`;
const box = (x, y, w, h, cls, title, lines = []) => {
  const all = [title, ...lines], lh = 17, cx = x + w / 2;
  const top = y + h / 2 - (all.length - 1) * lh / 2 + 4;
  return `<g class="box ${cls}"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="8"/>` +
    txt(cx, top, title, 't') + lines.map((l, i) => txt(cx, top + lh * (i + 1), l, 's')).join('') + `</g>`;
};

function sequence({ width, lifelines, steps, label, boxW = 170 }) {
  const boxH = 50, top = 12;
  let y = top + boxH + 22;
  const frames = [], parts = [], open = [];
  const X = i => lifelines[i].x;
  for (const s of steps) {
    if (s.msg) {
      const [a, b] = s.msg, lines = [].concat(s.label);
      const ay = y + lines.length * 15 + 2;
      parts.push(arrow([[X(a), ay], [X(b), ay]], { dashed: s.dashed }));
      parts.push(labelAbove((X(a) + X(b)) / 2, ay - 6, lines));
      y = ay + 20;
    } else if (s.note !== undefined) {
      const [i, j] = [].concat(s.note), cx = j === undefined ? X(i) : (X(i) + X(j)) / 2;
      const h = s.lines.length * 15 + 14, w = s.w;
      parts.push(`<g class="note"><rect x="${cx - w / 2}" y="${y}" width="${w}" height="${h}" rx="6"/>` +
        txt(cx, y + 19, s.lines, '', 15) + `</g>`);
      y += h + 18;
    } else if (s.frame === 'start') {
      open.push({ y0: y, ...s });
      y += 24;
    } else if (s.frame === 'end') {
      const f = open.pop(); y += 4;
      const x1 = X(f.from) - 52, x2 = X(f.to) + (f.pad ?? 52);
      frames.push(`<rect class="frame" x="${x1}" y="${f.y0}" width="${x2 - x1}" height="${y - f.y0}" rx="6"/>` +
        `<text class="flabel" x="${x1 + 8}" y="${f.y0 + 16}">${esc(f.label)}</text>`);
      y += 16;
    }
  }
  const H = y + 6;
  const heads = lifelines.map(l =>
    `<line class="life" x1="${l.x}" y1="${top + boxH}" x2="${l.x}" y2="${H}"/>` +
    box(l.x - boxW / 2, top, boxW, boxH, l.cls, l.title, l.sub ? [l.sub] : [])).join('');
  return `<svg viewBox="0 0 ${width} ${H}" style="max-width:${width}px" role="img" aria-label="${esc(label)}">` +
    frames.join('') + heads + parts.join('') + `</svg>`;
}

// ---------- Part 2 in sequence: search (as built) ----------
const L4 = [
  { x: 105, cls: 'app', title: 'Sulabha UI', sub: 'replaces the Angular UI' },
  { x: 420, cls: 'app', title: 'Candidate Search API', sub: 'Spring Boot, port 8000' },
  { x: 715, cls: 'llm', title: 'LLM', sub: 'OpenAI-compatible' },
  { x: 985, cls: 'db', title: 'PostgreSQL', sub: 'candidate_db' },
];
const search = sequence({
  width: 1090, label: 'Search flow', lifelines: L4, steps: [
    { msg: [0, 1], label: ['POST /query', '{ query, offset, limit }'] },
    { msg: [1, 3], label: 'query_cache lookup' },
    { msg: [3, 1], label: 'cached reading, if any', dashed: true },
    { frame: 'start', label: 'only on a cache miss', from: 1, to: 3 },
    { msg: [1, 2], label: ['read the query: role, domain,', 'job wording, skills, filters'] },
    { msg: [2, 1], label: 'parsed query (JSON)', dashed: true },
    { msg: [1, 3], label: 'store the reading in query_cache' },
    { frame: 'end' },
    { note: 1, w: 290, lines: ['Job: role + domain = direct match;', 'other roles in the same domain = related'] },
    { msg: [1, 3], label: ['find candidates: expertise rows (jobs, skills),', 'resume full-text (tsvector), candidate rows'] },
    { msg: [3, 1], label: 'matching rows', dashed: true },
    { note: 1, w: 310, lines: ['Check filters: pass / fail / not on file', 'Rank: skills met > job > match quality >', 'filters > score > years; cut the page'] },
    { msg: [1, 0], label: ['{ matches, total, parsed query }', 'partial matches listed after full ones'], dashed: true },
  ],
});

// ---------- 3. Part 1 in sequence: bulk upload (proposed) ----------
const L7 = [
  { x: 80, cls: 'app', title: 'Sulabha UI' },
  { x: 270, cls: 'aws new', title: 'Intake Lambda', sub: '/upload-bulk-resumes' },
  { x: 460, cls: 'aws', title: 'Work queue', sub: 'SQS' },
  { x: 650, cls: 'aws new', title: 'Ingest Lambda' },
  { x: 840, cls: 'app', title: 'Candidate Search API', sub: 'LLM + PostgreSQL' },
  { x: 1030, cls: 'aws', title: 'Error queue', sub: 'SQS' },
  { x: 1220, cls: 'aws new', title: 'Failed-records', sub: 'Lambda' },
];
const ingest = sequence({
  width: 1300, boxW: 160, label: 'Bulk resume upload', lifelines: L7, steps: [
    { msg: [0, 1], label: ['POST /upload-bulk-resumes', '[{ candidate_id, s3 link }]'] },
    { msg: [1, 2], label: ['one event per record', '{ candidate_id, s3 link }'] },
    { msg: [1, 0], label: '202 Accepted', dashed: true },
    { frame: 'start', label: 'for each record - in parallel, capped to the LLM rate limit', from: 2, to: 4, pad: 112 },
    { msg: [2, 3], label: 'record event' },
    { note: 3, w: 190, lines: ['download the file', 'from S3 (the link)'] },
    { msg: [3, 4], label: ['POST /upload-resume/{candidate_id}', 'the file'] },
    { note: 4, w: 200, lines: ['extract the text', 'duplicate check', 'LLM: suggest form, build profile', 'save in PostgreSQL'] },
    { msg: [4, 3], label: 'Saved / skipped (duplicate)', dashed: true },
    { frame: 'end' },
    { frame: 'start', label: 'on any error', from: 3, to: 5 },
    { msg: [3, 5], label: ['error message', '(candidate_id + reason)'] },
    { frame: 'end' },
    { msg: [0, 6], label: 'GET /failed-resumes' },
    { msg: [6, 5], label: 'read the error messages' },
    { msg: [5, 6], label: 'failed records', dashed: true },
    { msg: [6, 0], label: 'list of failed records', dashed: true },
  ],
});

// ---------- 4. Both parts at a glance ----------
const arch = (() => {
  const W = 1290, H = 500, p = [];
  p.push(`<rect class="band" x="180" y="10" width="770" height="290" rx="8"/>` + txt(195, 32, 'PART 1 - Bulk upload of resumes (new)', 'grp', 15, 'start'));
  p.push(`<rect class="band" x="180" y="320" width="770" height="130" rx="8"/>` + txt(195, 342, 'PART 2 - Candidate search (as built)', 'grp', 15, 'start'));
  p.push(`<rect class="vpc" x="1000" y="10" width="270" height="440" rx="10"/>` + txt(1015, 32, 'EXISTING APP', 'grp', 15, 'start'));
  p.push(box(20, 50, 140, 400, 'app', 'Sulabha UI', ['replaces', 'the Angular UI']));
  // part 1
  p.push(box(195, 50, 100, 100, 'aws dashed', 'API Gateway', ['optional']));
  p.push(box(330, 50, 190, 100, 'aws new', 'Intake Lambda', ['/upload-bulk-resumes', 'exposed to Sulabha UI only', 'one event per record']));
  p.push(box(550, 50, 130, 100, 'aws', 'SQS work queue', ['one event', 'per record']));
  p.push(box(710, 50, 190, 100, 'aws new', 'Ingest Lambda', ['reads a record from SQS', 'downloads the file from S3', 'POST /upload-resume/{id}']));
  p.push(box(330, 215, 190, 64, 'aws new', 'Failed-records Lambda', ['GET /failed-resumes']));
  p.push(box(550, 215, 130, 64, 'aws', 'SQS error queue', ['failed records']));
  p.push(box(730, 215, 150, 64, 'ext', 'S3 bucket', ['existing resumes']));
  p.push(arrow([[160, 100], [195, 100]]), arrow([[295, 100], [330, 100]]), arrow([[520, 100], [550, 100]]), arrow([[680, 100], [710, 100]]));
  p.push(arrow([[900, 100], [960, 100], [960, 175], [1020, 175]]));
  p.push(arrow([[805, 215], [805, 150]]), labelAbove(815, 190, 'GetObject', 'start'));
  p.push(arrow([[735, 150], [680, 240]]), labelAbove(700, 192, 'on error', 'end'));
  p.push(arrow([[550, 247], [520, 247]]));
  p.push(arrow([[330, 247], [160, 247]], { both: true }), labelAbove(245, 239, 'query failed records'));
  // part 2
  p.push(arrow([[160, 395], [975, 395], [975, 215], [1020, 215]], { both: true }), labelAbove(560, 387, 'POST /query   /   ranked candidates'));
  // existing app
  p.push(box(1020, 45, 230, 64, 'llm', 'LLM (external)', ['OpenAI-compatible API']));
  p.push(box(1020, 150, 230, 100, 'app', 'Candidate Search API', ['Spring Boot', 'POST /upload-resume/{id}', 'POST /query']));
  p.push(box(1020, 310, 230, 90, 'db', 'PostgreSQL', ['candidates, search profiles', 'query cache']));
  p.push(arrow([[1135, 150], [1135, 109]]), arrow([[1135, 250], [1135, 310]], { both: true }));
  p.push(`<rect class="swatch newsw" x="20" y="470" width="22" height="14" rx="3"/>` + txt(52, 482, 'red dashed outline = new code to write (in the API: the /upload-resume/{id} endpoint); everything else exists today or is a managed AWS service', 's', 15, 'start'));
  return `<svg viewBox="0 0 ${W} ${H}" style="max-width:${W}px" role="img" aria-label="Architecture: bulk upload and candidate search">${p.join('')}</svg>`;
})();


const css = `
:root{--bg:#fff;--fg:#1f2937;--muted:#64748b;--line:#475569;--box:#f1f5f9;--boxline:#94a3b8;
--app:#dbeafe;--appline:#2563eb;--ext:#fef3c7;--extline:#d97706;--aws:#ffedd5;--awsline:#ea580c;
--db:#dcfce7;--dbline:#16a34a;--llm:#ede9fe;--llmline:#7c3aed;--note:#fffbeb;--noteline:#d6c27a;
--new:#dc2626;--band:#f8fafc;--vpc:#2563eb;--card:#fff;--cardline:#e2e8f0}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#0f172a;--fg:#e5e7eb;--muted:#94a3b8;--line:#94a3b8;
--box:#1e293b;--boxline:#475569;--app:#172554;--appline:#60a5fa;--ext:#422006;--extline:#f59e0b;--aws:#431407;--awsline:#fb923c;
--db:#052e16;--dbline:#22c55e;--llm:#2e1065;--llmline:#a78bfa;--note:#292524;--noteline:#78716c;--new:#f87171;--band:#162033;--vpc:#60a5fa;--card:#111c33;--cardline:#334155}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:1240px;margin:0 auto;padding:24px 16px 64px}
h1{font-size:26px;margin:0 0 4px} h2{font-size:20px;margin:40px 0 6px} p{margin:4px 0 12px;color:var(--muted)}
nav{display:flex;flex-wrap:wrap;gap:8px 18px;margin:14px 0 0;font-size:14px} nav a{color:var(--appline);text-decoration:none}
.card{background:var(--card);border:1px solid var(--cardline);border-radius:10px;padding:12px;overflow-x:auto}
svg{width:100%;height:auto;display:block;margin:0 auto}
.box rect{fill:var(--box);stroke:var(--boxline);stroke-width:1.5}
.box.app rect{fill:var(--app);stroke:var(--appline)} .box.ext rect{fill:var(--ext);stroke:var(--extline)}
.box.aws rect{fill:var(--aws);stroke:var(--awsline)} .box.db rect{fill:var(--db);stroke:var(--dbline)}
.box.llm rect{fill:var(--llm);stroke:var(--llmline)}
.box.new rect{stroke:var(--new);stroke-width:2.5;stroke-dasharray:6 4} .box.dashed rect{stroke-dasharray:5 4}
.t{font:600 13px system-ui,sans-serif;fill:var(--fg)} .s{font:12px system-ui,sans-serif;fill:var(--muted)}
.lbl{font:12px system-ui,sans-serif;fill:var(--fg);paint-order:stroke;stroke:var(--bg);stroke-width:5px;stroke-linejoin:round}
.arr{fill:none;stroke:var(--line);stroke-width:1.6} .arr.dash{stroke-dasharray:6 4}
.life{stroke:var(--boxline);stroke-dasharray:3 5;stroke-width:1.2}
.note rect{fill:var(--note);stroke:var(--noteline)} .note text{font:12px system-ui,sans-serif;fill:var(--fg)}
.frame{fill:none;stroke:var(--muted);stroke-dasharray:4 4} .flabel{font:italic 12px system-ui,sans-serif;fill:var(--muted)}
.vpc{fill:none;stroke:var(--vpc);stroke-width:2} .band{fill:var(--band)} .grp{font:600 11px system-ui,sans-serif;fill:var(--muted);letter-spacing:.04em}
.legend{fill:var(--card);stroke:var(--cardline)} .swatch.aws{fill:var(--aws);stroke:var(--awsline)} .swatch.ext{fill:var(--ext);stroke:var(--extline)}
.swatch.newsw{fill:var(--aws);stroke:var(--new);stroke-width:2;stroke-dasharray:4 3}
ul{margin:6px 0 0;padding-left:20px;color:var(--muted)} li{margin:2px 0}
`;

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sulabha Candidate Search - architecture</title><style>${css}</style></head><body><main>
<svg width="0" height="0" style="position:absolute"><defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#64748b"/></marker></defs></svg>
<h1>Sulabha candidate search - architecture</h1>
<p>Sulabha UI takes the place of the Angular UI. Part 1 (bulk upload of the S3 resumes) is new; part 2 (candidate search) is the API as built today.</p>
<nav><a href="#overview">Overview</a><a href="#ingest">Part 1: bulk upload</a><a href="#search">Part 2: candidate search</a></nav>
<h2 id="overview">Overview: bulk upload and search</h2>
<p>Part 1 loads the resumes from S3 through queues; part 2 is the regular candidate search. Both use the same API and database.</p>
<div class="card">${arch}</div>
<h2 id="ingest">Part 1 in sequence: bulk upload (proposed)</h2>
<p>Sulabha sends S3 file links with a candidate id for each. The Intake Lambda queues one event per record; the Ingest Lambda sends each to the API. Errors go to an error queue that Sulabha can query.</p>
<div class="card">${ingest}</div>
<ul><li><code>/upload-resume/{id}</code> is new. It runs what today's <code>/upload_resumes_bulk</code> runs per file (extract text, duplicate check, suggest the form, build the profile, save), but takes the candidate id from Sulabha instead of generating a UUID.</li>
<li>Sending the same id again updates that candidate, so a queue retry never creates a second record.</li>
<li>A duplicate resume is skipped, not reported as an error.</li></ul>
<h2 id="search">Part 2 in sequence: candidate search (as built)</h2>
<p>The LLM reads the query only on a cache miss; matching and ranking run in code against Postgres.</p>
<div class="card">${search}</div>
</main></body></html>`;

const out = process.argv[2] ?? new URL('architecture.html', import.meta.url);
writeFileSync(out, html);
console.log('wrote', String(out), html.length, 'bytes');
