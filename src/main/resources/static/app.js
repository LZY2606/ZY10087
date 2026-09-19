const $ = (id) => document.getElementById(id);

let state = null;
let currentKind = 'topology';
let latestComparison = null;
let playbackSteps = [];
let playbackIndex = 0;

const api = {
  async get(url) {
    const response = await fetch(url);
    if (!response.ok) throw await errorFrom(response);
    return response.json();
  },
  async post(url, body) {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? '' : JSON.stringify(body)
    });
    if (!response.ok) throw await errorFrom(response);
    return response.json();
  }
};

async function errorFrom(response) {
  let body;
  try { body = await response.json(); } catch { body = { message: response.statusText }; }
  return Object.assign(new Error(body.message || response.statusText), { status: response.status, body });
}

function toast(message, isError = false) {
  const node = $('toast');
  node.textContent = message;
  node.style.display = 'block';
  node.style.background = isError ? '#8d1f25' : '#10233d';
  setTimeout(() => { node.style.display = 'none'; }, 5200);
}

async function refreshState() {
  state = await api.get('/api/state');
  renderApproval();
  renderRevisionLists();
  renderRuleOptions();
  renderFaultOptions();
  renderApprovalOptions();
  renderTopology(latestRevision('topologies'));
  if (!$('topologyEditor').value) $('topologyEditor').value = JSON.stringify(parse(latestRevision('topologies')), null, 2);
}

function renderApprovalOptions() {
  const options = (items, selected) => items.map(item =>
    `<option value="${item.revisionId}" ${item.revisionId === selected ? 'selected' : ''}>${item.revisionId}</option>`).join('');
  $('approveTopology').innerHTML = options(state.topologies, state.approval.topologyRevisionId);
  $('approveRule').innerHTML = options(state.rules, state.approval.ruleRevisionId);
  $('approveScenario').innerHTML = options(state.scenarios, state.approval.scenarioRevisionId);
}

function latestRevision(key) {
  return state[key][0];
}

function renderApproval() {
  $('approval').textContent = JSON.stringify(state.approval, null, 2);
}

function renderRuleOptions() {
  const selected = $('candidateRule').value;
  $('candidateRule').innerHTML = state.rules
    .filter(rule => rule.revisionId !== state.approval.ruleRevisionId)
    .map(rule => `<option value="${rule.revisionId}">${rule.revisionId}</option>`).join('');
  if (selected) $('candidateRule').value = selected;
  const weak = state.rules.find(rule => rule.entityId === 'weak-flank');
  if (!$('candidateRule').value && weak) $('candidateRule').value = weak.revisionId;
  fillRuleEditorFromSelectedCandidate();
}

function renderFaultOptions() {
  const current = $('faultScenario').value;
  $('faultScenario').innerHTML = state.scenarios.map(item =>
    `<option value="${item.revisionId}">${item.revisionId}</option>`).join('');
  if (current) $('faultScenario').value = current;
}

function renderRevisionLists() {
  const select = $('revisionSelect');
  const key = currentKind === 'topology' ? 'topologies' : currentKind === 'rule' ? 'rules' : 'scenarios';
  select.innerHTML = state[key]
    .map(item => `<option value="${item.revisionId}">${item.revisionId} · ${item.sourceFingerprint.slice(0, 10)}</option>`)
    .join('');
  showRevision();
}

async function showRevision() {
  const id = $('revisionSelect').value;
  if (!id) {
    $('revisionPayload').textContent = '';
    return;
  }
  const revision = await api.get(`/api/revisions/${currentKind}/${id}`);
  $('revisionPayload').textContent = JSON.stringify({
    revisionId: revision.revisionId,
    baseRevisionId: revision.baseRevisionId,
    versionNumber: revision.versionNumber,
    sourceFingerprint: revision.sourceFingerprint,
    topologyFingerprint: revision.topologyFingerprint,
    ruleFingerprint: revision.ruleFingerprint,
    faultOfRevisionId: revision.faultOfRevisionId,
    payload: JSON.parse(revision.payload)
  }, null, 2);
}

function fillRuleEditorFromSelectedCandidate() {
  const id = $('candidateRule').value;
  const rule = state.rules.find(item => item.revisionId === id);
  if (rule) {
    const payload = JSON.parse(rule.payload);
    payload.id = `${payload.id}-edit-${Date.now()}`;
    payload.name = `${payload.name}（浏览器编辑候选）`;
    $('ruleEditor').value = JSON.stringify(payload, null, 2);
    $('ruleBase').value = id;
  }
}

function parse(revision) {
  return revision ? JSON.parse(revision.payload) : null;
}

function renderTopology(revision) {
  const topology = parse(revision);
  const svg = $('topologySvg');
  if (!topology) {
    svg.innerHTML = '';
    return;
  }
  const positions = new Map();
  topology.sections.forEach((section, index) => {
    const row = index < 3 ? 55 : index < 5 ? 145 : 235;
    const col = 70 + (index % 3) * 175 + (index >= 5 ? 175 : 0);
    positions.set(section.id, { x: col, y: row, type: 'section' });
  });
  topology.switches.forEach((sw, index) => positions.set(sw.id, { x: 475, y: 100 + index * 95, type: 'switch' }));
  const signalForSection = new Map(topology.signals.map(signal => [signal.section, signal.id]));
  const links = topology.links.map(link => {
    const a = positions.get(link.from.device);
    const b = positions.get(link.to.device);
    if (!a || !b) return '';
    const x1 = a.x + 36, y1 = a.y, x2 = b.x - 34, y2 = b.y;
    const cls = link.direction === 'BOTH' ? 'rail' : 'rail forward';
    return `<line class="${cls}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>
      <text class="small-label" x="${(x1+x2)/2}" y="${(y1+y2)/2 - 8}">${link.id} · ${link.direction}</text>`;
  }).join('');
  const nodes = topology.sections.map(section => {
    const p = positions.get(section.id);
    const signal = signalForSection.get(section.id);
    return `<g>
      <rect class="section-node" x="${p.x-40}" y="${p.y-20}" width="80" height="40" rx="8"/>
      <text class="node-label" x="${p.x}" y="${p.y}">${section.id}</text>
      ${signal ? `<circle class="signal-node" cx="${p.x-52}" cy="${p.y-15}" r="12"/><text class="node-label" x="${p.x-52}" y="${p.y-15}" style="font-size:10px">${signal}</text>` : ''}
    </g>`;
  }).join('');
  const switches = topology.switches.map(sw => {
    const p = positions.get(sw.id);
    return `<g>
      <polygon class="switch-node" points="${p.x},${p.y-24} ${p.x+34},${p.y+20} ${p.x-34},${p.y+20}"/>
      <text class="node-label" x="${p.x}" y="${p.y+2}">${sw.id}</text>
      <text class="small-label" x="${p.x}" y="${p.y+38}">${sw.commonPort} / ${sw.branchPorts.join(',')}</text>
    </g>`;
  }).join('');
  const routes = topology.routes.map((route, index) =>
    `<text class="small-label" x="70" y="${292 + index * 15}" text-anchor="start">${route.id}: ${route.entrySignal} → ${route.sections.join(' → ')}; 防护 ${route.explicitFlankGuards.map(g => g.switchId + ':' + g.position).join(',') || '无'}</text>`
  ).join('');
  svg.innerHTML = `<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L0,6 L9,3 z" fill="#596778"/></marker></defs>${links}${nodes}${switches}${routes}`;
}

async function compareRules() {
  try {
    latestComparison = await api.post('/api/verifications/compare', {
      candidateRuleRevisionId: $('candidateRule').value,
      maxDepth: Number($('maxDepth').value),
      maxStates: Number($('maxStates').value)
    });
    renderComparison();
    const bad = latestComparison.candidate.conclusion === 'COUNTEREXAMPLE' ? latestComparison.candidate
      : latestComparison.baseline.conclusion === 'COUNTEREXAMPLE' ? latestComparison.baseline : null;
    playbackSteps = bad ? bad.counterexample : [];
    playbackIndex = playbackSteps.length;
    renderStep();
    toast('比较完成');
  } catch (error) {
    toast(error.message, true);
  }
}

function renderReport(name, report) {
  const cls = report.conclusion === 'SAFE' ? 'safe' : report.conclusion === 'COUNTEREXAMPLE' ? 'bad' : 'bound';
  const rejections = report.rejectedTransitions.map(item =>
    `<li><b>${item.processId}/${item.action.id}</b> ${item.code} ×${item.occurrences}: ${item.reason}</li>`).join('');
  const trace = (report.counterexample || []).map(step =>
    `<li>${step.step}. ${step.processId}/${step.action.id} — ${step.action.displayName()}
      <div>${step.violations.map(v => `<b>${v.invariant}</b>: ${v.detail}`).join('<br>')}</div>
    </li>`).join('');
  return `<article class="report ${cls}">
    <h3>${name}</h3>
    <span class="metric">结论 <strong>${report.conclusion}</strong></span>
    <span class="metric">探索状态 <strong>${report.exploredStates}</strong></span>
    <span class="metric">迁移 <strong>${report.exploredTransitions}</strong></span>
    <span class="metric">反例长度 <strong>${report.counterexampleLength}</strong></span>
    <p>${report.conclusionReason}</p>
    <details open><summary>拒绝原因</summary><ul>${rejections || '<li>无</li>'}</ul></details>
    <details ${trace ? 'open' : ''}><summary>反例步骤</summary><ol>${trace || '<li>无</li>'}</ol></details>
  </article>`;
}

function renderComparison() {
  if (!latestComparison) return;
  $('comparison').innerHTML =
    renderReport('当前批准规则', latestComparison.baseline) +
    renderReport('候选规则', latestComparison.candidate);
}

function renderStep() {
  $('stepCounter').textContent = `${playbackIndex} / ${playbackSteps.length}`;
  if (!playbackSteps.length) {
    $('stepDetail').textContent = '该比较没有可播放反例；BOUND_REACHED 不是安全证明。';
    return;
  }
  const step = playbackSteps[Math.max(0, playbackIndex - 1)];
  if (!step) {
    $('stepDetail').textContent = '初始状态：所有进路 IDLE，设备正常，区段空闲。';
    return;
  }
  $('stepDetail').textContent = JSON.stringify({
    step: step.step,
    process: step.processId,
    action: step.action,
    guards: step.guards,
    effects: step.effects,
    violations: step.violations,
    before: step.before,
    after: step.after
  }, null, 2);
}

async function saveRuleCandidate() {
  try {
    const payload = JSON.parse($('ruleEditor').value);
    const revision = await api.post('/api/revisions/rule', {
      payload: JSON.stringify(payload),
      baseRevisionId: $('ruleBase').value || null,
      source: 'browser:' + (crypto.randomUUID ? crypto.randomUUID() : Date.now())
    });
    toast(`已创建不可改候选 ${revision.revisionId}`);
    await refreshState();
  } catch (error) {
    if (error.status === 409) showConflict(error.body);
    else toast(error.message, true);
  }
}

async function insertFault() {
  try {
    const revision = await api.post(`/api/scenarios/${$('faultScenario').value}/fault`, {
      deviceId: $('faultDevice').value,
      afterActionIndex: Number($('faultAfter').value),
      source: 'manual-ui'
    });
    toast(`已生成故障场景新版本 ${revision.revisionId}`);
    await refreshState();
  } catch (error) {
    toast(error.message, true);
  }
}

async function simulateConflict() {
  try {
    const base = state.rules.find(rule => rule.entityId === 'safe-interlocking') || state.rules[0];
    const payload = JSON.parse(base.payload);
    const firstPayload = { ...payload, id: 'browser-conflict-demo', name: '第一浏览器提交：增强信号防护文案' };
    await api.post('/api/revisions/rule', { payload: JSON.stringify(firstPayload), baseRevisionId: base.revisionId, source: 'browser-A' });
    const secondPayload = { ...payload, id: 'browser-conflict-demo', name: '第二浏览器基于旧 base 后到', flankProtection: !payload.flankProtection };
    await api.post('/api/revisions/rule', { payload: JSON.stringify(secondPayload), baseRevisionId: base.revisionId, source: 'browser-B' });
  } catch (error) {
    if (error.status === 409) {
      showConflict(error.body);
      toast('已复现后到提交冲突');
    } else {
      toast(error.message, true);
    }
  }
}

function showConflict(conflict) {
  $('conflictResult').textContent = JSON.stringify({
    ...conflict,
    replayableMerge: [
      '1. 拉取 currentRevisionId 的 payload；',
      '2. 保留 incomingFingerprint 对应编辑意图；',
      '3. 手工或程序合并冲突字段；',
      '4. 以 currentRevisionId 作为新的 baseRevisionId 提交。'
    ]
  }, null, 2);
}

async function exportPackage() {
  const topologyId = JSON.parse(latestRevision('topologies').payload).id;
  const candidateRuleId = JSON.parse(state.rules.find(rule => rule.revisionId === $('candidateRule').value).payload).id;
  const response = await fetch(`/api/exports/validation-package?topologyId=${encodeURIComponent(topologyId)}&candidateRuleId=${encodeURIComponent(candidateRuleId)}`);
  if (!response.ok) {
    toast('导出失败', true);
    return;
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'rail-validation-package.zip';
  link.click();
  URL.revokeObjectURL(url);
}

async function importPackage(file) {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch('/api/imports/validation-package', { method: 'POST', body: form });
  if (!response.ok) {
    toast('导入校验失败：报告指纹或反例不一致', true);
    return;
  }
  const result = await response.json();
  latestComparison = { baseline: result.baseline, candidate: result.candidate };
  renderComparison();
  const bad = result.candidate.conclusion === 'COUNTEREXAMPLE' ? result.candidate
    : result.baseline.conclusion === 'COUNTEREXAMPLE' ? result.baseline : null;
  playbackSteps = bad ? bad.counterexample : [];
  playbackIndex = playbackSteps.length;
  renderStep();
  toast('另一台机器重算报告指纹一致，最小反例已载入');
}

$('refresh').addEventListener('click', refreshState);
$('validateTopology').addEventListener('click', validateTopologyFromEditor);
$('saveTopology').addEventListener('click', saveTopologyCandidate);
$('approveSelected').addEventListener('click', approveSelected);
$('runCompare').addEventListener('click', compareRules);
$('saveRule').addEventListener('click', saveRuleCandidate);
$('insertFault').addEventListener('click', insertFault);
$('simulateConflict').addEventListener('click', simulateConflict);
$('exportPackage').addEventListener('click', exportPackage);
$('importPackage').addEventListener('change', event => importPackage(event.target.files[0]));
$('candidateRule').addEventListener('change', fillRuleEditorFromSelectedCandidate);
$('revisionSelect').addEventListener('change', showRevision);
document.querySelectorAll('.tabs button').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('.tabs button').forEach(item => item.classList.remove('active'));
  button.classList.add('active');
  currentKind = button.dataset.kind;
  renderRevisionLists();
}));
$('firstStep').onclick = () => { playbackIndex = 0; renderStep(); };
$('prevStep').onclick = () => { playbackIndex = Math.max(0, playbackIndex - 1); renderStep(); };
$('nextStep').onclick = () => { playbackIndex = Math.min(playbackSteps.length, playbackIndex + 1); renderStep(); };
$('lastStep').onclick = () => { playbackIndex = playbackSteps.length; renderStep(); };

refreshState().catch(error => toast(error.message, true));

async function validateTopologyFromEditor() {
  try {
    const topology = JSON.parse($('topologyEditor').value);
    const result = await api.post('/api/topology/validate', topology);
    renderIssues('topologyImportIssues', result.issues);
    toast(result.valid ? '拓扑检查通过' : '拓扑存在错误，未接收为版本', !result.valid);
  } catch (error) {
    toast(error.message, true);
  }
}

async function saveTopologyCandidate() {
  try {
    const payload = JSON.stringify(JSON.parse($('topologyEditor').value));
    const revision = await api.post('/api/revisions/topology', {
      payload,
      baseRevisionId: $('topologyBase').value || null,
      source: 'manual-ui'
    });
    toast(`已接收不可改拓扑候选 ${revision.revisionId}`);
    await refreshState();
  } catch (error) {
    if (error.status === 409) showConflict(error.body);
    else if (error.body?.issues) renderIssues('topologyImportIssues', error.body.issues);
    toast(error.message, true);
  }
}

async function approveSelected() {
  try {
    const approval = await api.post('/api/approvals', {
      topologyRevisionId: $('approveTopology').value,
      ruleRevisionId: $('approveRule').value,
      scenarioRevisionId: $('approveScenario').value
    });
    toast(`新批准冻结点 ${approval.approvalId}`);
    await refreshState();
  } catch (error) {
    toast(error.message, true);
  }
}

function renderIssues(elementId, issues) {
  $(elementId).innerHTML = (issues || []).map(issue =>
    `<div class="issue ${issue.error ? 'error' : 'warning'}"><b>${issue.code}</b>：${issue.message}</div>`).join('');
}
