'use strict';

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const state = {
  tops: [], rules: [], scenarios: [], approval: null,
  sel: { top: null, rule: null, scenario: null },
  ruleEditing: null, ruleBaseCopy: null,
  step: null, traceReport: null,
};

const FLAG_LABELS = {
  requireSectionsFree: ['建立进路要求区段空闲', 'guard: 区段占用时拒绝 REQUEST_ROUTE'],
  enforceRouteMutex: ['进路互斥锁闭', 'guard: 冲突进路/共享区段拒绝锁闭'],
  requireSwitchPosition: ['道岔位置检查', 'guard: 道岔未在要求位置时拒绝锁闭'],
  blockSwitchUnderMovement: ['占用/锁闭时禁止扳动道岔', 'guard: 区段有车或道岔被锁时拒绝 MOVE_SWITCH'],
  requireFlankProtection: ['侧向（flank）防护', 'guard/invariant I3: 侧向邻线必须空闲'],
  releaseOnlyAfterClear: ['出清后才能释放', 'guard: 区段仍占用时拒绝释放'],
  enforceReleaseOrder: ['按入口→出口顺序释放', 'guard/invariant I4: 禁止跳序释放'],
  signalRequiresFullLock: ['信号需完全锁闭', 'guard/invariant I5: 未全锁闭不得开放信号'],
};

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let data = text ? JSON.parse(text) : null;
  if (!res.ok) throw { status: res.status, data };
  return data;
}

function toast(msg, bad) {
  const t = $('#toast');
  t.textContent = msg;
  t.style.borderColor = bad ? 'var(--bad)' : 'var(--accent)';
  t.classList.remove('hidden');
  clearTimeout(toast._h);
  toast._h = setTimeout(() => t.classList.add('hidden'), 4200);
}

const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const pretty = (o) => JSON.stringify(o, null, 2);
const badge = (c) => {
  const label = { SAFE: '已证明安全', COUNTEREXAMPLE: '发现反例',
    LIMIT_REACHED: '达到上限（未证明）', TOPOLOGY_INVALID: '拓扑无效' }[c] || c;
  return `<span class="badge ${c}">${label}</span>`;
};

document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => {
  $$('.tabs button').forEach((x) => x.classList.remove('active'));
  $$('.tab').forEach((x) => x.classList.remove('active'));
  b.classList.add('active');
  $('#tab-' + b.dataset.tab).classList.add('active');
}));

function option(list, value, label) {
  return `<option value="${esc(value)}" ${value === arguments[3] ? 'selected' : ''}>${esc(label)}</option>`;
}

// ---------------- data loaders ----------------

async function loadAll() {
  const [tops, rules, scenarios, approval] = await Promise.all([
    api('/topologies'), api('/rules'), api('/scenarios'), api('/approval')]);
  state.tops = tops; state.rules = rules; state.scenarios = scenarios; state.approval = approval;
  renderDashboard(); renderTops(); renderRules(); renderScenarios(); fillSelectors();
}

async function renderDashboard() {
  const reports = await api('/reports').catch(() => []);
  const ap = state.approval;
  const cards = [
    ['拓扑版本', state.tops.length], ['规则版本', state.rules.length],
    ['场景版本', state.scenarios.length],
    ['批准状态', ap && ap.approved ? `★ ${esc(ap.ruleVersion)}` : '未批准'],
  ];
  $('#dashCards').innerHTML = cards.map(([t, n]) =>
    `<div class="card"><div class="n">${n}</div><div class="t">${t}</div></div>`).join('');
  $('#approvalBox').textContent = ap && ap.approved
    ? `规则 ${ap.ruleVersion} (${ap.ruleSetId})
拓扑 ${ap.topologyId}
  fp ${ap.topologyFingerprint}
场景 ${ap.scenarioId}
  fp ${ap.scenarioFingerprint}
规则 fp ${ap.ruleFingerprint}
批准时间 ${ap.approvedAt} 批准人 ${ap.approvedBy}`
    : '尚无批准版本。批准将冻结拓扑/规则/场景三者的指纹。';
  $('#reportList').innerHTML = reports.length ? reports.map((r) => `
    <div class="item" data-report="${esc(r.id)}">
      <div>${badge(r.conclusion)} <span class="id">${esc(r.id)}</span>
        <div class="meta">规则 ${esc(r.ruleVersion)} · 反例 ${r.traceLength} 步 · 上限 ${r.bound}</div></div>
      <div class="meta">${esc((r.createdAt || '').replace('T', ' ').slice(0, 19))}</div>
    </div>`).join('') : '<div class="issue info">还没有报告</div>';
  $$('#reportList .item').forEach((el) => el.addEventListener('click', async () => {
    const detail = await api('/reports/' + el.dataset.report);
    state.traceReport = detail;
    document.querySelector('[data-tab="verify"]').click();
    renderTrace(detail);
  }));
}

function fillSelectors() {
  const topOpts = state.tops.map((t) =>
    `<option value="${esc(t.id)}">${esc(t.revision)} · ${esc(t.name)} · ${esc(t.id)}</option>`).join('');
  const ruleOpts = state.rules.map((r) =>
    `<option value="${esc(r.id)}">${esc(r.version)}${r.approved ? ' ★' : ''} · ${esc(r.id)}</option>`).join('');
  const scOpts = state.scenarios.map((s) =>
    `<option value="${esc(s.id)}">v${s.versionNo} · ${esc(s.name)}</option>`).join('');
  [['#verifyTop', topOpts], ['#pkgTop', topOpts]].forEach(([sel, html]) => $(sel).innerHTML = html);
  [['#verifyRule', ruleOpts], ['#pkgRule', ruleOpts], ['#cmpRule', ruleOpts]].forEach(([sel, html]) => $(sel).innerHTML = html);
  [['#verifyScenario', scOpts], ['#pkgScenario', scOpts], ['#cmpScenario', scOpts]].forEach(([sel, html]) => $(sel).innerHTML = html);
  const ap = state.approval;
  if (ap && ap.approved) {
    $('#verifyTop').value = ap.topologyId; $('#verifyRule').value = ap.ruleSetId;
    $('#verifyScenario').value = ap.scenarioId;
    $('#pkgTop').value = ap.topologyId; $('#pkgRule').value = ap.ruleSetId;
    $('#pkgScenario').value = ap.scenarioId;
    $('#cmpScenario').value = ap.scenarioId;
  }
}

function renderTops() {
  $('#topList').innerHTML = state.tops.map((t) => `
    <div class="item ${state.sel.top === t.id ? 'selected' : ''}" data-id="${esc(t.id)}">
      <div><span class="id">r${t.revision} · ${esc(t.name)}</span>
        <div class="meta">${esc(t.fingerprint.slice(0, 16))}… · 问题 ${t.issues} · ${esc((t.receivedAt || '').slice(0, 19).replace('T', ' '))}</div></div>
      <div>${t.issues === 0 ? '✅' : '⚠️'}</div>
    </div>`).join('') || '<div class="issue info">没有拓扑，先导入</div>';
  $$('#topList .item').forEach((el) => el.addEventListener('click', () => selectTopology(el.dataset.id)));
}

async function selectTopology(id) {
  state.sel.top = id;
  renderTops();
  const d = await api('/topologies/' + id);
  drawTopology(d.payload);
  $('#topSummary').textContent = pretty(d.summary);
  $('#topIssues').innerHTML = d.issues.length
    ? d.issues.map((i) => `<div class="issue bad"><b>${esc(i.code)}</b> <code>${esc(i.ref)}</code> ${esc(i.message)}</div>`).join('')
    : '<div class="issue ok">悬空连接 / 方向不一致 / 不可达区段检查全部通过</div>';
  $('#stepCtx').dataset.topology = id;
}

// ---------------- topology canvas ----------------

function drawTopology(t) {
  const cv = $('#topCanvas'); const ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, cv.width, cv.height);
  if (!t) return;
  const P = {}; t.points.forEach((p) => { P[p.id] = p; });
  if (t.points.some((p) => p.x == null)) { autoLayout(t); t.points.forEach((p) => { P[p.id] = p; }); }
  const minX = Math.min(...t.points.map((p) => p.x)), maxX = Math.max(...t.points.map((p) => p.x));
  const minY = Math.min(...t.points.map((p) => p.y)), maxY = Math.max(...t.points.map((p) => p.y));
  const sx = (x) => 30 + (x - minX) / Math.max(1, maxX - minX) * (cv.width - 60);
  const sy = (y) => 30 + (y - minY) / Math.max(1, maxY - minY) * (cv.height - 60);

  ctx.lineWidth = 3; ctx.strokeStyle = '#6f83b0';
  const line = (a, b) => {
    if (!P[a] || !P[b]) return;
    ctx.beginPath(); ctx.moveTo(sx(P[a].x), sy(P[a].y)); ctx.lineTo(sx(P[b].x), sy(P[b].y)); ctx.stroke();
  };
  t.links.forEach((l) => line(l.endA, l.endB));
  t.sections.forEach((s) => { ctx.strokeStyle = '#9fb4e6'; line(s.endA, s.endB); });
  // switch position arcs
  t.switches.forEach((sw) => {
    ctx.strokeStyle = '#f5a623';
    line(sw.plus, sw.straight);
    ctx.setLineDash([6, 5]); line(sw.plus, sw.minus); ctx.setLineDash([]);
  });
  // labels
  ctx.fillStyle = '#e7ecf6'; ctx.font = '11px monospace'; ctx.textAlign = 'center';
  const secMid = (s, dy) => {
    if (!P[s.endA] || !P[s.endB]) return;
    ctx.fillText(s.id, (sx(P[s.endA].x) + sx(P[s.endB].x)) / 2,
      (sy(P[s.endA].y) + sy(P[s.endB].y)) / 2 + dy);
  };
  t.sections.forEach((s) => secMid(s, -6));
  t.points.forEach((p) => {
    ctx.beginPath(); ctx.fillStyle = p.boundary ? '#38c172' : '#5b9dff';
    ctx.arc(sx(p.x), sy(p.y), p.boundary ? 6 : 5, 0, 7); ctx.fill();
    ctx.fillStyle = '#93a0bd'; ctx.fillText(p.id + (p.boundary ? ' ◇' : ''), sx(p.x), sy(p.y) + 18);
  });
  t.switches.forEach((sw) => {
    const p = P[sw.plus]; if (!p) return;
    ctx.fillStyle = '#f5a623'; ctx.fillText('⤢ ' + sw.id, sx(p.x), sy(p.y) - 10);
  });
  t.signals.forEach((sg) => {
    const p = P[sg.atPoint]; if (!p) return;
    ctx.fillStyle = '#ff5d5d'; ctx.fillText('🚦' + sg.id, sx(p.x) + 28, sy(p.y) - 4);
  });
  $('#topCanvasHint').textContent = `节点 ${t.points?.length || 0} / 道岔 ${t.switches?.length || 0} / 区段 ${t.sections?.length || 0} / 进路 ${t.routes?.length || 0}`;
}

function autoLayout(t) {
  t.points.forEach((p, i) => { p.x = 100 + (i % 5) * 120; p.y = 100 + Math.floor(i / 5) * 120; });
}

$('#btnImportTop').addEventListener('click', async () => {
  try {
    const payload = JSON.parse($('#topJson').value);
    const d = await api('/topologies', { method: 'POST', body: { name: $('#topName').value, payload } });
    toast(`已接收为新版本 ${d.id}（原始证据不可改写）`);
    await loadAll(); await selectTopology(d.id);
  } catch (e) { toast('导入失败: ' + (e.data?.error || e.message), true); }
});

$('#btnSampleTop').addEventListener('click', async () => {
  // fetch the newest topology payload the seeder created, or show template
  if (state.tops[0]) {
    const d = await api('/topologies/' + state.tops[0].id);
    $('#topJson').value = pretty(d.payload);
  } else {
    $('#topJson').value = JSON.stringify({ points: [], switches: [], sections: [], links: [], signals: [], routes: [] }, null, 2);
  }
});

// ---------------- rules ----------------

function defaultRule() {
  return { version: $('#ruleVersion').value, note: $('#ruleNote').value,
    maxShuntSpeed: Number($('#ruleSpeed').value), ...collectFlags() };
}
function collectFlags() {
  const o = {};
  $$('#ruleFlags input[type=checkbox]').forEach((c) => { o[c.dataset.flag] = c.checked; });
  return o;
}

function renderRuleFlags(rule) {
  $('#ruleFlags').innerHTML = Object.entries(FLAG_LABELS).map(([k, [label, desc]]) => `
    <label class="flag"><input type="checkbox" data-flag="${k}" ${rule[k] ? 'checked' : ''}/>
      <span><b>${label}</b><br/><small>${desc}</small></span></label>`).join('');
  $('#ruleSpeed').value = rule.maxShuntSpeed ?? 25;
}

function renderRules() {
  $('#ruleList').innerHTML = state.rules.map((r) => `
    <div class="item ${state.ruleEditing === r.id ? 'selected' : ''}" data-id="${esc(r.id)}" data-v="${r.lockVersion}">
      <div>${r.approved ? '<span class="star">★</span> ' : ''}<span class="id">${esc(r.version)}</span>
        <div class="meta">${esc(r.note || '')} · ${esc(r.fingerprint.slice(0, 12))}… · v${r.lockVersion}</div></div>
      <div class="meta">${esc(r.id)}</div>
    </div>`).join('');
  $$('#ruleList .item').forEach((el) => el.addEventListener('click', () => loadRuleIntoEditor(el.dataset.id)));
}

async function loadRuleIntoEditor(id) {
  const item = state.rules.find((r) => r.id === id);
  state.ruleEditing = id;
  state.ruleBaseCopy = JSON.parse(JSON.stringify(item.flags));
  $('#ruleVersion').value = item.version;
  $('#ruleNote').value = item.note || '';
  renderRuleFlags(item.flags);
  renderRules();
}

$('#btnCreateRule').addEventListener('click', async () => {
  try {
    const r = await api('/rules', { method: 'POST', body: { payload: defaultRule(), note: $('#ruleNote').value } });
    toast('已创建候选 ' + r.id);
    await loadAll(); loadRuleIntoEditor(r.id);
  } catch (e) { toast('创建失败: ' + (e.data?.error || e.message), true); }
});

$('#btnSaveRule').addEventListener('click', async () => {
  if (!state.ruleEditing) return toast('先在右侧选择一个候选版本', true);
  const item = state.rules.find((r) => r.id === state.ruleEditing);
  try {
    await api('/rules/' + state.ruleEditing, {
      method: 'POST', body: { baseVersion: item.lockVersion, payload: defaultRule() } });
    toast('保存成功'); await loadAll(); loadRuleIntoEditor(state.ruleEditing);
  } catch (e) {
    if (e.status === 409) { showConflict(e.data); }
    else toast('保存失败: ' + (e.data?.error || e.message), true);
  }
});

function showConflict(c) {
  toast('检测到并发编辑冲突，已展开三方内容', true);
  $('#ruleConflictBox').classList.remove('hidden');
  $('#ruleConflictBox').textContent =
    `${c.message}\n\n您基于的版本: ${c.clientBaseVersion}\n服务器当前版本: ${c.currentVersion}\n\n` +
    `您的内容:\n${pretty(c.clientPayload)}\n\n服务器当前内容:\n${pretty(c.currentPayload)}`;
}

$('#btnMergeRule').addEventListener('click', async () => {
  if (!state.ruleEditing) return toast('先在右侧选择一个候选版本', true);
  const base = state.ruleBaseCopy || defaultRule();
  try {
    const m = await api('/rules/' + state.ruleEditing + '/merge', {
      method: 'POST', body: { base, incoming: defaultRule() } });
    const el = $('#mergeOut');
    el.innerHTML = (m.conflicts.length
      ? m.conflicts.map((c) => `<div class="issue warn"><b>冲突参数 ${esc(c.flag)}</b>：基线 ${c.base}，您的 ${c.yours}，当前 ${c.current} → 合并保留 ${c.merged}（可重新选择后再合并）</div>`).join('')
      : '<div class="issue ok">没有硬冲突，双方改动已合并</div>') +
      `<div class="issue info">合并结果候选: ${esc(m.mergedId)}</div>`;
    toast('合并完成: ' + m.mergedId);
    await loadAll();
  } catch (e) { toast('合并失败: ' + (e.data?.error || e.message), true); }
});

// ---------------- scenarios + time stepping ----------------

function renderScenarios() {
  $('#scenarioList').innerHTML = state.scenarios.map((s) => `
    <div class="item ${state.sel.scenario === s.id ? 'selected' : ''}" data-id="${esc(s.id)}">
      <div><span class="id">${esc(s.id)}</span> · ${esc(s.name)}
        <div class="meta">${s.parentVersion ? '父版本 ' + esc(s.parentVersion) + ' · ' : ''}fp ${esc(s.fingerprint.slice(0, 12))}…</div></div>
      <div class="meta">v${s.versionNo}</div>
    </div>`).join('');
  $$('#scenarioList .item').forEach((el) => el.addEventListener('click', () => selectScenario(el.dataset.id)));
}

async function selectScenario(id) {
  state.sel.scenario = id;
  renderScenarios();
  const d = await api('/scenarios/' + id);
  $('#scJson').value = pretty(d.payload);
  $('#stepCtx').innerHTML = `<span>拓扑 <select id="stepTopSel"></select></span>
    <span>规则 <select id="stepRuleSel"></select></span>
    <span>场景 <code>${esc(id)}</code></span>`;
  const ts = $('#stepTopSel'), rs = $('#stepRuleSel');
  ts.innerHTML = state.tops.map((t) => `<option value="${t.id}">${esc(t.name)}</option>`).join('');
  rs.innerHTML = state.rules.map((r) => `<option value="${r.id}">${esc(r.version)}</option>`).join('');
  const ap = state.approval;
  if (ap && ap.approved) {
    ts.value = ap.topologyId; rs.value = ap.ruleSetId;
  } else if (state.sel.top) ts.value = state.sel.top;
}

$('#btnSaveScenario').addEventListener('click', async () => {
  try {
    const topId = $('#stepTopSel')?.value || state.sel.top || $('#verifyTop').value;
    const payload = JSON.parse($('#scJson').value);
    const d = await api(`/scenarios?topologyId=${encodeURIComponent(topId || '')}`,
      { method: 'POST', body: { name: $('#scName').value, payload } });
    toast('已生成新场景版本 ' + d.id);
    await loadAll(); selectScenario(d.id);
  } catch (e) { toast('保存失败: ' + (e.data?.error || e.message), true); }
});

$('#btnSampleScenario').addEventListener('click', async () => {
  const id = state.scenarios.find((s) => s.name.includes('竞争'))?.id;
  if (id) { const d = await api('/scenarios/' + id); $('#scJson').value = pretty(d.payload); }
  else toast('没有竞争场景样例', true);
});

function stepCtxIds() {
  return { topologyId: $('#stepTopSel').value, ruleSetId: $('#stepRuleSel').value,
    scenarioId: state.sel.scenario };
}

$('#btnStepInit').addEventListener('click', async () => {
  try {
    const { topologyId, ruleSetId, scenarioId } = stepCtxIds();
    state.step = await api('/step/initial', { method: 'POST', body: { topologyId, ruleSetId, scenarioId } });
    renderStep('已复位到场景初始状态');
  } catch (e) { toast('复位失败: ' + (e.data?.error || e.message), true); }
});

$('#btnStepOne').addEventListener('click', async () => {
  try {
    const { topologyId, ruleSetId } = stepCtxIds();
    const op = { op: $('#stepOp').value, target: $('#stepTarget').value.trim(),
      position: $('#stepPos').value || null, actor: $('#stepTrain').value || 'T1' };
    state.step = await api('/step/one', { method: 'POST',
      body: { topologyId, ruleSetId, state: state.step.state, operation: op } });
    renderStep(state.step.accepted ? state.step.effect : ('被拒绝: ' + state.step.rejectReason));
  } catch (e) { toast('步进失败: ' + (e.data?.error || e.message), true); }
});

function renderStep(title) {
  const box = $('#stepState');
  const inv = state.step.invariants || [];
  box.textContent = `${title}\n\n不变量: ${inv.length ? '\n  ✗ ' + inv.join('\n  ✗ ') : '全部满足 ✓'}\n\n状态:\n${pretty(state.step.state)}`;
  box.style.borderColor = inv.length ? 'var(--bad)' : 'var(--line)';
}

$('#btnFaultVersion').addEventListener('click', async () => {
  if (!state.sel.scenario) return toast('先选择基础场景版本', true);
  const kind = prompt('故障类型：SECTION 还是 SWITCH？', 'SECTION');
  if (!kind) return;
  const deviceId = prompt('设备 id（如 S1 或 SW1）？');
  if (!deviceId) return;
  const d = await api(`/scenarios/${state.sel.scenario}/faults`,
    { method: 'POST', body: { kind, deviceId, clear: false } });
  toast(`手工故障已形成新场景版本 ${d.id}（旧版本未改写）`);
  await loadAll(); selectScenario(d.id);
});

// ---------------- verification + trace diagnostics ----------------

$('#btnVerify').addEventListener('click', () => runVerify(false));
$('#btnVerifyApproved').addEventListener('click', () => runVerify(true));

async function runVerify(approved) {
  const bound = Number($('#verifyBound').value);
  const body = approved ? null : {
    topologyId: $('#verifyTop').value, ruleSetId: $('#verifyRule').value,
    scenarioId: $('#verifyScenario').value };
  try {
    const r = await api(`/verify${approved ? '/approved' : ''}?bound=${bound}`,
      body ? { method: 'POST', body } : { method: 'POST' });
    state.traceReport = r;
    renderTrace(r);
    await renderDashboard();
  } catch (e) { toast('验证失败: ' + (e.data?.error || e.message), true); }
}

function renderTrace(r) {
  const summary = $('#verifySummary');
  const limit = r.limitReached ? '<div class="issue warn">已达到显式上限 —— 这不是安全证明，扩大上限或精简场景后重跑</div>' : '';
  if (r.conclusion === 'SAFE') {
    summary.innerHTML = `<div class="issue ok">${badge('SAFE')} 在显式上限 ${r.bound} 内穷尽 ${r.reachableStates} 个可达状态（归并 ${r.mergedStates} 个对称状态），未发现违反。</div>${limit}`;
  } else if (r.conclusion === 'LIMIT_REACHED') {
    summary.innerHTML = `<div class="issue warn">${badge('LIMIT_REACHED')} 探索 ${r.exploredTransitions} 次迁移后停止；与“证明安全”是不同结论。</div>`;
  } else if (r.conclusion === 'COUNTEREXAMPLE') {
    const finalV = r.trace.length ? r.trace[r.trace.length - 1].violations : [];
    summary.innerHTML = `<div class="issue bad">${badge('COUNTEREXAMPLE')} 最短反例长度 <b>${r.trace.length}</b> 步；可达状态 ${r.reachableStates}（归并 ${r.mergedStates}）。最终违反：<ul>${finalV.map((v) => `<li>${esc(v)}</li>`).join('')}</ul></div>${limit}`;
  } else {
    summary.innerHTML = `<div class="issue bad">${badge(r.conclusion)} 拓扑存在 ${r.topologyIssues.length} 个问题：<ul>${r.topologyIssues.map((i) => `<li>${esc(i)}</li>`).join('')}</ul></div>`;
  }
  const rejectSummary = Object.entries(r.rejectionReasons || {})
    .map(([k, n]) => `<code>${esc(k)}</code> ×${n}`).join('，');
  summary.innerHTML += `<div class="issue info">守卫拒绝统计：${rejectSummary || '无'}</div>`;

  $('#traceView').innerHTML = r.trace.map((s) => `
    <div class="step ${s.violations && s.violations.length ? 'violate' : ''}" data-step="${s.step}">
      <div class="head"><span class="num">#${s.step}</span><span class="actor">${esc(s.actor || '')}</span>
        <span class="trig">${esc(s.trigger)}</span>
        ${s.violations && s.violations.length ? '<span class="badge COUNTEREXAMPLE">违反不变量</span>' : ''}</div>
      <div class="eff">${esc(s.effect)}${s.rejectReason ? ' — <b style="color:var(--warn)">' + esc(s.rejectReason) + '</b>' : ''}</div>
    </div>`).join('') || '<div class="issue info">无反例轨迹（安全或达到上限）</div>';
  $$('#traceView .step').forEach((el) => el.addEventListener('click', () => {
    const st = r.trace.find((x) => x.step === Number(el.dataset.step));
    $('#traceState').textContent =
      `触发条件: ${st.trigger}\n执行方: ${st.actor || '-'}\n效果: ${st.effect}\n` +
      (st.rejectReason ? `拒绝原因: ${st.rejectReason}\n` : '') +
      `未满足不变量:\n${(st.violations || []).map((v) => '  ✗ ' + v).join('\n') || '  （全部满足）'}\n\n` +
      `本步后状态:\n${pretty(st.state)}`;
  }));
  if (r.trace.length) $('#traceView .step')[$('#traceView .step').length - 1]?.click();
}

// ---------------- candidate comparison ----------------

$('#btnCompare').addEventListener('click', async () => {
  try {
    const r = await api('/compare?bound=' + Number($('#cmpBound').value), {
      method: 'POST', body: { candidateRuleId: $('#cmpRule').value, scenarioId: $('#cmpScenario').value } });
    const row = (name, x) => `
      <tr><td>${name}</td>
        <td>${badge(x.conclusion)}</td>
        <td>${x.reachableStates}</td>
        <td>${x.mergedStates ?? '-'}</td>
        <td>${x.exploredTransitions}</td>
        <td>${x.counterexampleLength ?? '—'}</td>
        <td><code>${esc(Object.entries(x.rejectionReasons || {}).map(([k, v]) => k + '=' + v).join(', ') || '无')}</code></td></tr>`;
    const deltas = r.deltas;
    $('#cmpOut').innerHTML = `
      <table class="cmp">
        <tr><th>版本</th><th>结论</th><th>可达状态</th><th>归并</th><th>迁移数</th><th>反例长度</th><th>拒绝原因</th></tr>
        ${row('批准版 ' + r.approved.ruleVersion, r.approved)}
        ${row('候选 ' + r.candidate.ruleVersion, r.candidate)}
      </table>
      <div class="issues">
        <div class="issue info">可达状态差: ${deltas.reachableStates}；迁移数差: ${deltas.exploredTransitions}；反例长度差: ${formatLenDelta(deltas.counterexampleLengthDelta)}</div>
        <div class="issue info">候选新增拒绝: <code>${esc(Object.entries(deltas.newRejections).map(([k,v])=>k+' +'+v).join(', ') || '无')}</code></div>
        <div class="issue info">候选消失拒绝: <code>${esc(Object.entries(deltas.removedRejections).map(([k,v])=>k+' -'+v).join(', ') || '无')}</code></div>
      </div>
      <h3>候选反例触发序列</h3>
      <pre class="box">${esc((r.candidate.counterexampleTriggers || []).map((t,i)=>`${i+1}. ${t}`).join('\n') || '无反例')}\n\n最终不变量:\n${esc((r.candidate.finalViolations || []).join('\n') || '全部满足')}</pre>`;
  } catch (e) { toast('对比失败: ' + (e.data?.error || e.message), true); }
});

function formatLenDelta(d) {
  if (d === 0) return '0（两版一致）';
  if (d > 100000000) return (d > 0 ? '新增反例' : '候选反例消失（差=' + d + '）');
  return (d > 0 ? '+' + d : String(d));
}

// ---------------- export / import replay ----------------

$('#btnExport').addEventListener('click', async () => {
  try {
    const pkg = await api('/export?bound=5000', { method: 'POST', body: {
      topologyId: $('#pkgTop').value, ruleSetId: $('#pkgRule').value,
      scenarioId: $('#pkgScenario').value } });
    const blob = new Blob([JSON.stringify(pkg, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `verification-package-${pkg.packageFingerprint.slice(0, 10)}.json`;
    a.click();
    toast('验证包已下载，可在另一台机器导入重放');
  } catch (e) { toast('导出失败: ' + (e.data?.error || e.message), true); }
});

$('#pkgFile').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  try {
    const pkg = JSON.parse(await file.text());
    const r = await api('/import-replay', { method: 'POST', body: pkg });
    $('#replayOut').textContent =
      `重放结论: ${r.rerunConclusion}\n反例长度: ${r.rerunTraceLength}\n跨机器选中同一条最小反例: ${r.replayMatch ? '是 ✅' : '否 ❌'}\n` +
      (r.diffs && r.diffs.length ? '差异:\n  ' + r.diffs.join('\n  ') + '\n' : '') +
      `\n重放触发序列:\n${(r.rerunTriggers || []).map((t, i) => `${i + 1}. ${t}`).join('\n')}\n\n` +
      `最终违反:\n${(r.rerunViolations || []).join('\n') || '无'}`;
    $('#replayOut').style.borderColor = r.replayMatch ? 'var(--ok)' : 'var(--bad)';
  } catch (err) { toast('重放失败: ' + (err.data?.error || err.message), true); }
});

// ---------------- bootstrap ----------------

(async function init() {
  renderRuleFlags(new (function () {
    Object.assign(this, { requireSectionsFree: true, enforceRouteMutex: true,
      requireSwitchPosition: true, blockSwitchUnderMovement: true, requireFlankProtection: true,
      releaseOnlyAfterClear: true, enforceReleaseOrder: true, signalRequiresFullLock: true,
      maxShuntSpeed: 25 });
  })());
  try {
    await loadAll();
    if (state.tops[0]) selectTopology(state.tops[0].id);
    if (state.rules[0]) loadRuleIntoEditor(state.rules.find((r) => !r.approved)?.id || state.rules[0].id);
    if (state.scenarios[0]) selectScenario(state.scenarios[0].id);
    if (state.tops[0]) $('#topJson').value = '';
  } catch (e) {
    toast('初始化失败: ' + (e.data?.error || e.message), true);
  }
})();
