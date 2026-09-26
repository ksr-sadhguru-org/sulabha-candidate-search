// End-to-end check of the running API (localhost:8000) against expected-results.json.
// By default it reuses the data already loaded and only runs the checks. Reset only what a change needs:
//   --fresh                         clear ALL data, upload every test resume (after upload/profile changes)
//   --reupload="Deepa Nair,Kiran Rao"  delete and re-upload just these people's resumes
//   --clear-cache                   forget saved query readings first (after query prompt changes)
//   --only=G1,C2                    run only these scenario IDs
// Checks that need the special uploads (B1, B2, A10) run only with --fresh; otherwise they are skipped.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const API = 'http://localhost:8000';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIR = path.join(HERE, 'Candidate Resumes for Tests');
const spec = JSON.parse(fs.readFileSync(path.join(HERE, 'expected-results.json'), 'utf8'));
const args = process.argv.slice(2);
const fresh = args.includes('--fresh');
const clearCache = args.includes('--clear-cache');
const reupload = (args.find(a => a.startsWith('--reupload=')) || '').slice(11).split(',').map(n => n.trim()).filter(Boolean);
const only = (args.find(a => a.startsWith('--only=')) || '').slice(7).split(',').filter(Boolean);
const LATER = ['Rohan Kulkarni - Senior Java Developer (Resubmission).docx', 'Vikram Patil - Java Developer (Updated).docx', 'Scanned Resume - Unreadable.pdf'];

const personOf = file => file.split(' - ')[0];
const results = [];
const skip = (id, label) => console.log(`SKIP ${id} ${label} (needs --fresh)`);
const record = (id, label, ok, detail = '') => { results.push({ id, label, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${label}${ok ? '' : '  -> ' + detail}`); };

async function call(method, url, body, isForm) {
  const res = await fetch(API + url, { method, body: isForm ? body : body && JSON.stringify(body), headers: isForm || !body ? {} : { 'Content-Type': 'application/json' } });
  const text = await res.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, json };
}

const extract = file => {
  const fd = new FormData();
  fd.append('file', new Blob([fs.readFileSync(path.join(DIR, file))]), file);
  return call('POST', '/extract_resume_text', fd, true);
};

async function uploadOne(file) {
  const ex = await extract(file);
  if (ex.status !== 200) return { file, extract: ex };
  if (ex.json.duplicate_of) return { file, duplicate: ex.json.duplicate_of };
  const form = (await call('POST', '/suggest_applicant_details', { resume_text: ex.json.extracted_text })).json;
  Object.assign(form, spec.manual_fields[personOf(file)] || {});
  const up = await call('POST', '/upload_resume', { candidate_id: crypto.randomUUID(), resume_name: file, resume_text: ex.json.extracted_text, applicant_details: form });
  return { file, upload: up.json };
}

async function pool(items, n, fn) {
  const out = []; let i = 0;
  await Promise.all(Array.from({ length: n }, async () => { while (i < items.length) { const k = i++; out[k] = await fn(items[k]); } }));
  return out;
}

// ---------- upload phase ----------
const later = {};
const uploadAll = async files => {
  const t0 = Date.now();
  const ups = await pool(files.filter(f => !LATER.includes(f)), 4, async f => { const r = await uploadOne(f); console.log(`  ${r.upload?.status ? 'saved' : 'NOT saved'} ${f} ${r.upload?.message ?? JSON.stringify(r.extract?.json ?? r.duplicate)}`); return r; });
  for (const f of LATER.filter(f => files.includes(f))) later[f] = await uploadOne(f);
  console.log(`Uploaded ${files.length} file(s) in ${Math.round((Date.now() - t0) / 1000)}s`);
  return ups;
};
if (fresh) {
  await call('DELETE', '/clear_all_data');
  const ups = await uploadAll(fs.readdirSync(DIR));
  record('F2', 'bulk upload: all saved', ups.every(u => u.upload?.status), ups.filter(u => !u.upload?.status).map(u => u.file).join(', '));
} else if (reupload.length) {
  const onFile = (await call('GET', '/candidates')).json.filter(c => reupload.includes(personOf(c.resume_name)));
  for (const c of onFile) await call('DELETE', `/candidates/${c.candidate_id}`);
  const files = fs.readdirSync(DIR).filter(f => reupload.includes(personOf(f)));
  console.log(`Re-uploading ${reupload.join(', ')}: removed ${onFile.length} record(s), uploading ${files.length} file(s)`);
  await uploadAll(files);
}
if (clearCache) console.log('Query cache cleared:', (await call('DELETE', '/query_cache')).json.cleared_queries, 'saved queries');

const list = (await call('GET', '/candidates')).json;
const idToPerson = Object.fromEntries(list.map(c => [c.candidate_id, personOf(c.resume_name)]));
const details = {};
const detailOf = async person => {
  const c = list.filter(x => personOf(x.resume_name) === person);
  if (!c.length) return null;
  details[person] ??= (await call('GET', `/candidates/${c[0].candidate_id}`)).json;
  return details[person];
};

// ---------- upload checks ----------
const nameMatch = (entry, want) => {
  const w = want.toLowerCase();
  if (w.endsWith('*')) return entry.name.toLowerCase().startsWith(w.slice(0, -1)) || entry.terms.some(t => t.startsWith(w.slice(0, -1)));
  return entry.name.toLowerCase().includes(w) || entry.terms.includes(w);
};
const resumeEntries = d => d.expertise.filter(e => e.source === 'resume');

for (const u of spec.uploads) {
  if (only.length && !only.includes(u.id)) continue;
  const label = u.candidate ?? u.file ?? JSON.stringify(u.candidate_count ?? u.order_by_score);
  try {
    if ((u.expect === 'already_exists' || u.expect_error) && !later[u.file]) { skip(u.id, u.file); continue; }
    if (u.expect === 'already_exists') {
      const r = later[u.file]; record(u.id, `${u.file} flagged as duplicate`, !!r?.duplicate, JSON.stringify(r)); continue;
    }
    if (u.expect_error) {
      const r = later[u.file]; record(u.id, `${u.file} rejected with a message`, r?.extract?.status === 400, JSON.stringify(r?.extract ?? r)); continue;
    }
    if (u.expect === 'updated_existing' && !later[u.file]) skip(u.id, `${u.file} updates existing`);
    else if (u.expect === 'updated_existing') {
      const r = later[u.file]; record(u.id, `${u.file} updates existing`, !!r?.upload?.updated_existing, JSON.stringify(r?.upload));
    }
    if (u.candidate_count) for (const [p, n] of Object.entries(u.candidate_count)) {
      const got = list.filter(c => personOf(c.resume_name) === p).length; record(u.id, `${p} count = ${n}`, got === n, `got ${got}`);
    }
    if (u.order_by_score) {
      const scores = [];
      for (const p of u.order_by_score) scores.push(Math.max(0, ...resumeEntries(await detailOf(p)).filter(e => e.terms.includes(u.term)).map(e => e.score)));
      record(u.id, `${u.term} scores ordered ${u.order_by_score.join(' > ')}`, scores.every((s, i) => i === 0 || scores[i - 1] > s), scores.join(' > '));
      continue;
    }
    if (!u.candidate) continue;
    const d = await detailOf(u.candidate);
    if (!d) { record(u.id, label, false, 'candidate not found'); continue; }
    const entries = resumeEntries(d);
    const show = entries.map(e => `${e.name}(${e.score},${e.years}y)`).join('; ');
    for (const want of u.expertise ?? []) {
      const e = entries.find(x => nameMatch(x, want.name));
      const ok = e && (want.years_min == null || e.years >= want.years_min) && (want.years_max == null || e.years <= want.years_max);
      record(u.id, `${u.candidate}: entry "${want.name}"${want.years_min != null ? ` ${want.years_min}-${want.years_max ?? ''}y` : ''}`, !!ok, show);
    }
    for (const [n, max] of Object.entries(u.entry_count_max_for ?? {})) {
      const c = entries.filter(e => e.name.toLowerCase().includes(n)).length; record(u.id, `${u.candidate}: at most ${max} "${n}" entry`, c <= max, show);
    }
    if (u.max_score_non_education != null) {
      const bad = entries.filter(e => e.kind !== 'education' && e.score > u.max_score_non_education);
      record(u.id, `${u.candidate}: non-education scores <= ${u.max_score_non_education}`, !bad.length, show);
    }
    if (u.terms_include) {
      const all = entries.flatMap(e => e.terms); const missing = u.terms_include.filter(t => !all.includes(t));
      record(u.id, `${u.candidate}: terms include ${u.terms_include.join(', ')}`, !missing.length, `missing ${missing}`);
    }
    if (u.lower_score) {
      const [a, b] = u.lower_score.map(w => entries.find(e => nameMatch(e, w)));
      record(u.id, `${u.candidate}: ${u.lower_score[0]} scored below ${u.lower_score[1]}`, !!(a && b && a.score < b.score), show);
    }
    if (u.form?.skill_competencies_pattern) {
      const sc = d.applicant_details.skill_competencies ?? '';
      record(u.id, `${u.candidate}: skill competencies with years`, new RegExp(u.form.skill_competencies_pattern).test(sc), sc);
    }
    if (u.form?.job_location) {
      const loc = d.applicant_details.job_location ?? '';
      const ok = u.note ? loc.endsWith(u.form.job_location.split(', ').at(-1)) : loc === u.form.job_location;
      record(u.id, `${u.candidate}: location "${u.form.job_location}"`, ok, loc);
    }
  } catch (e) { record(u.id, label, false, e.message); }
}

// ---------- query checks ----------
const FIELD = { location: 'jobLocation', experience: 'experience', is_meditator: 'isMeditator', stay_in_ashram: 'stayInAshram', languages: 'languages' };
const cache = {};
// limit 100: every check sees the whole ranked list, not just the first page.
const search = async q => (cache[q] ??= (await call('POST', '/query', { query: q, limit: 100 })).json);
const people = r => (r.results ?? []).map(m => idToPerson[m.candidate_id] ?? m.name);

for (const c of spec.queries) {
  if (only.length && !only.includes(c.id)) continue;
  const tag = `"${c.q}"`;
  try {
    const r = await search(c.q);
    const names = people(r);
    const brief = names.map((n, i) => `${n}[${r.results[i].match_type}]`).join(', ') || `(none) msg=${r.message}`;
    if (c.first) { const top = new Set(names.slice(0, c.first.length)); record(c.id, `${tag} first: ${c.first.join(', ')}`, c.first.every(p => top.has(p)), brief); }
    if (c.include) { const miss = c.include.filter(p => !names.includes(p)); record(c.id, `${tag} includes ${c.include.join(', ')}`, !miss.length, `missing ${miss} | ${brief}`); }
    if (c.exclude) { const bad = c.exclude.filter(p => names.includes(p)); record(c.id, `${tag} excludes ${c.exclude.join(', ')}`, !bad.length, `present ${bad} | ${brief}`); }
    if (c.not_skill_match) {
      const bad = c.not_skill_match.filter(p => { const i = names.indexOf(p); return i >= 0 && ['profile', 'related'].includes(r.results[i].match_type); });
      record(c.id, `${tag} no skill match for ${c.not_skill_match.join(', ')}`, !bad.length, `skill-matched ${bad} | ${brief}`);
    }
    if (c.related_below) {
      const direct = names.filter((_, i) => r.results[i].match_type === 'profile').length;
      const bad = c.related_below.filter(p => { const i = names.indexOf(p); return i >= 0 && (r.results[i].match_type === 'profile' || i < direct); });
      record(c.id, `${tag} ${c.related_below.join(', ')} only as related, below direct matches`, !bad.length, `misplaced ${bad} | ${brief}`);
    }
    for (const [a, b] of c.order ?? []) {
      const ia = names.indexOf(a), ib = names.indexOf(b);
      record(c.id, `${tag} ${a} above ${b}`, ia >= 0 && ib >= 0 && ia < ib, brief);
    }
    for (const [p, checks] of Object.entries(c.filters ?? {})) {
      const m = r.results?.[names.indexOf(p)];
      for (const [k, want] of Object.entries(checks)) {
        const f = m?.filters.find(x => x.field === FIELD[k] || (k === 'experience' && x.field.startsWith('experience')));
        record(c.id, `${tag} ${p} ${k} = ${want}`, f?.status === want, m ? JSON.stringify(m.filters.map(x => `${x.field}:${x.status}`)) : 'not in results');
      }
    }
    if (c.count_max != null) record(c.id, `${tag} at most ${c.count_max} results`, names.length <= c.count_max, brief);
    if (c.message) record(c.id, `${tag} message ~ "${c.message}"`, (r.message ?? '').toLowerCase().includes(c.message.toLowerCase()), r.message);
    if (c.page_size) {
      const pages = [];
      for (let offset = 0; offset < r.total; offset += c.page_size) {
        pages.push(...people((await call('POST', '/query', { query: c.q, offset, limit: c.page_size })).json));
      }
      record(c.id, `${tag} pages of ${c.page_size} join up to the full ${r.total} results in order`,
        r.total > c.page_size && JSON.stringify(pages) === JSON.stringify(names), `pages [${pages}] vs all [${names}]`);
    }
    if (c.parsed_location) {
      const l = r.parsed?.filters?.location; const got = l && [l.city, l.state, l.country].filter(Boolean).join(', ');
      record(c.id, `${tag} location parsed as ${c.parsed_location}`, got === c.parsed_location, got);
    }
    if (c.same_results_as) {
      const other = people(await search(c.same_results_as));
      record(c.id, `${tag} same results as "${c.same_results_as}"`, JSON.stringify([...names].sort()) === JSON.stringify([...other].sort()), `${names} vs ${other}`);
    }
    if (c.repeat) {
      const runs = []; for (let i = 0; i < c.repeat; i++) runs.push(people((await call('POST', '/query', { query: c.q, limit: 100 })).json).join('|'));
      record(c.id, `${tag} same order over ${c.repeat} runs`, new Set(runs).size === 1, runs.join(' // '));
    }
  } catch (e) { record(c.id, tag, false, e.message); }
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
fs.writeFileSync(path.join(HERE, 'test-results.json'), JSON.stringify({ results, queries: cache }, null, 2));
process.exitCode = failed.length ? 1 : 0;
