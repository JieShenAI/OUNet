'use strict';

// A deliberately self-contained interaction prototype: no fetch, no portal login,
// no local credential storage, and no changes to Windows settings.
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const icon = (name) => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const state = { mode: 'online', monitor: true, failures: 0, blocked: false, busy: false, phase: 'checking', externalOnline: false, authenticationSuccess: false, migrationPending: false, interval: 2, page: 'overview', taskToken: 0, tray: true };
let toastTimer;
let wizardStep = 0;
let installStep = 0;
const wizardData = { wifi: 'HUAWEI-Jie_5G', username: 'demo_student', password: 'demo_only_123', migrate: true, startup: false };
let dialogVersion = 0;
let previousFocus;
const events = [
  { type: 'success', text: '外网检查通过，未发送校园网认证请求', time: timeNow() },
  { type: 'info', text: '自动守护已启动，检查间隔 2 分钟', time: timeNow(-120) },
  { type: 'info', text: '已读取演示配置，失败保护就绪', time: timeNow(-121) }
];

function timeNow(offset = 0) {
  return new Date(Date.now() + offset * 1000).toLocaleTimeString('zh-CN', { hour12: false });
}
function escapeText(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function toast(message) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').hidden = false;
  toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 4200);
}
function addEvent(type, text) {
  events.unshift({ type, text, time: timeNow() });
  if (events.length > 60) events.pop();
  renderEvents();
}
function eventHTML(event) {
  const glyph = event.type === 'success' ? 'check' : event.type === 'warning' ? 'info' : 'clock';
  return `<div class="event-row"><span class="event-dot ${event.type}">${icon(glyph)}</span><span class="event-copy">${escapeText(event.text)}</span><time class="event-time">${escapeText(event.time)}</time></div>`;
}
function renderEvents() {
  $('#recent-list').innerHTML = events.slice(0, 2).map(eventHTML).join('');
  const filter = $('#log-filter').value;
  const filtered = events.filter((event) => filter === 'all' || event.type === filter);
  $('#full-event-list').innerHTML = filtered.length ? filtered.map(eventHTML).join('') : '<p class="empty-events">暂时没有这类记录</p>';
}
function go(page) {
  state.page = page;
  $$('.page').forEach((el) => el.classList.toggle('active', el.id === `page-${page}`));
  $$('.nav-item').forEach((el) => {
    el.classList.toggle('active', el.dataset.page === page);
    if (el.dataset.page === page) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current');
  });
  $('#main-content').scrollTop = 0;
}
function setSwitch(button, enabled) {
  button.classList.toggle('on', enabled);
  button.setAttribute('aria-checked', String(enabled));
}
function render() {
  const modes = {
    online: { title: '网络已连接', pill: '外网可用', description: '当前电脑可正常访问互联网，无需提交校园网认证。', style: '', glyph: 'check' },
    offline: { title: '网络暂时断开', pill: '等待恢复', description: '已连接目标 Wi-Fi，但外网暂不可用。检查后将尝试校园网认证。', style: 'warning', glyph: 'refresh' },
    wrongwifi: { title: '当前使用其他 Wi-Fi', pill: '等待目标网络', description: '为保留当前连接，暂不自动切换网络。连接目标 Wi-Fi 后再检查。', style: 'muted', glyph: 'wifi' },
    permission: { title: '需要检查 Wi-Fi 权限', pill: '需要你的操作', description: '无法读取当前 Wi-Fi。请检查 Windows 定位与桌面应用访问设置。', style: 'warning', glyph: 'info' }
  };
  let view = modes[state.mode] || modes.online;
  if (!state.monitor && !['permission', 'wrongwifi'].includes(state.mode)) view = { title: '自动守护已暂停', pill: state.mode === 'online' ? '外网可用 · 守护暂停' : '守护暂停', description: '周期检查已停止。你仍可手动检查网络，或重新开启自动守护。', style: 'muted', glyph: 'pause' };
  if (state.blocked) view = { title: '认证已保护性暂停', pill: '连续 3 轮失败', description: '为避免反复请求，已停止校园网认证。仍可检查外网，或确认配置后重置保护。', style: 'warning', glyph: 'shield' };
  if (state.migrationPending) view = { title: '旧任务等待处理', pill: '配置已保存', description: '检测到原脚本任务尚未处理。新守护保持暂停，处理后再启用，避免重复认证。', style: 'warning', glyph: 'info' };
  if (state.busy) view = { title: state.phase === 'authenticating' ? '正在恢复校园网…' : '正在检查连接…', pill: '请稍候', description: state.phase === 'authenticating' ? '模拟校园网认证，随后复查外网是否可用。' : state.blocked ? '只检测外网，不向校园网提交认证。' : '先检查外网；仅在需要时尝试恢复连接。', style: 'checking', glyph: 'refresh' };

  $('#status-hero').className = `status-hero ${view.style}`;
  $('#connection-title').textContent = view.title;
  $('#connection-pill').innerHTML = `<span class="status-dot"></span><span>${view.pill}</span>`;
  $('#connection-description').textContent = view.description;
  $('.visual-caption').textContent = state.busy ? 'CHECKING CONNECTION.' : state.blocked ? 'PROTECTED, QUIETLY.' : state.mode === 'online' ? 'CONNECTED, QUIETLY.' : 'HERE WHEN YOU NEED IT.';
  $('.quiet-badge').innerHTML = `<span></span>${state.blocked ? '失败保护' : state.monitor ? '静默守护' : '守护暂停'}`;
  $('.core-check').innerHTML = icon(view.glyph);
  $('#check-network').disabled = state.busy;
  $('#check-network span').textContent = state.busy ? '检查中…' : state.migrationPending ? '处理旧任务' : state.blocked ? '只检查外网' : state.mode === 'wrongwifi' ? '连接目标 Wi-Fi' : state.mode === 'permission' ? '查看处理方法' : '检查并连接';
  $('#hero-secondary').innerHTML = state.blocked ? `重置失败保护${icon('arrow')}` : `查看连接记录${icon('arrow')}`;
  $('#hero-secondary').disabled = state.busy;
  $('#protection-details').disabled = state.busy;
  $('#reset-protection').disabled = state.busy;
  $('#simulate-recovery').disabled = state.busy || state.mode !== 'offline';
  $('#monitor-toggle').disabled = state.busy;
  setSwitch($('#monitor-toggle'), state.monitor);
  $('#monitor-label').textContent = state.monitor ? '已开启' : '已暂停';
  $('#monitor-description').textContent = !state.monitor ? '周期检查已停止，手动检查仍可使用。' : state.blocked ? `每 ${state.interval} 分钟仅检查外网；校园网认证已暂停。` : `每 ${state.interval} 分钟检查外网，只在断网时尝试恢复。`;
  $('#failure-number').textContent = state.failures;
  $('#protection-label').textContent = state.blocked ? '已触发' : state.failures ? '记录中' : '待命中';
  $('#protection-description').textContent = state.blocked ? '等待外网恢复或手动重置，不会到点自动重试。' : '10 分钟内连续 3 轮失败，暂停校园网认证。';
  $('#protection-card').classList.toggle('blocked', state.blocked);
  $$('.budget-track i').forEach((el, index) => el.classList.toggle('used', index < state.failures));
  $('#budget-caption').textContent = state.blocked ? '已停止认证请求' : state.failures ? `连续 ${state.failures} 轮未恢复` : '当前没有连续失败';
  $('#protection-details').textContent = state.blocked ? '重置保护' : '了解保护';
  $('#current-wifi').textContent = state.mode === 'wrongwifi' ? 'Coffee-WiFi（演示）' : state.mode === 'permission' ? '暂时无法读取' : $('#target-wifi').value;
  $('#footer-status').textContent = state.blocked ? '失败保护中 · 认证已暂停' : state.monitor ? '自动守护运行中' : '自动守护已暂停';
  $('#footer-dot').style.color = state.blocked ? '#bd8737' : state.monitor ? '#4d9d7c' : '#9baabc';
  $('#check-caption').textContent = state.busy ? '正在执行模拟检查，不会发送真实网络请求' : state.blocked ? '继续检测外网 · 暂停校园网认证' : state.monitor ? `上次检查 刚刚 · 下次检查约 ${state.interval} 分钟后` : '周期检查暂停 · 可随时手动检查';
  $('#tray-state').textContent = state.blocked ? '保护已触发 · 认证暂停' : state.monitor ? (state.mode === 'online' ? '外网可用 · 守护中' : '等待恢复 · 守护中') : '自动守护已暂停';
  $('#tray-pause').textContent = state.monitor ? '暂停自动守护' : '恢复自动守护';
}

function selectScenario(mode) {
  state.taskToken += 1;
  state.busy = false;
  state.phase = 'checking';
  state.externalOnline = false;
  state.migrationPending = false;
  state.authenticationSuccess = mode === 'offline-success';
  state.monitor = mode !== 'paused';
  state.mode = mode === 'protected' || mode === 'offline-success' ? 'offline' : mode === 'paused' ? 'online' : mode;
  state.failures = mode === 'protected' ? 3 : 0;
  state.blocked = mode === 'protected';
  render();
  go('overview');
  addEvent(mode === 'protected' || mode === 'permission' ? 'warning' : 'info', `切换到演示场景：${$('#scenario').selectedOptions[0].textContent}`);
  if (mode === 'offline') toast('断网演示：点击“检查并连接”模拟一轮失败，连续三轮后触发保护。');
  if (mode === 'offline-success') toast('恢复演示：点击“检查并连接”，查看认证和外网复查成功的流程。');
}

async function checkNetwork() {
  if (state.busy) return;
  if (state.migrationPending) { showWizard(2); return; }
  if (state.mode === 'permission') { showPermissionHelp(); return; }
  if (state.mode === 'wrongwifi') {
    showModal(`${modalBody('切换到目标 Wi-Fi？', `<p>这会在正式版中断开当前 Wi-Fi，并连接已保存的 ${escapeText($('#target-wifi').value)} 配置。此处仅演示，不改变电脑网络。</p>`, 'wifi')}${modalFooter('<button class="button secondary" data-dismiss>保留当前连接</button><button class="button primary" id="confirm-wifi">模拟切换</button>')}`);
    $('#confirm-wifi').onclick = () => { closeModal(); state.mode = 'offline'; $('#scenario').value = 'offline'; render(); addEvent('info', '演示：已切换到目标 Wi-Fi，等待检查'); };
    return;
  }
  const token = ++state.taskToken;
  const checkOnly = state.blocked;
  state.busy = true;
  state.phase = 'checking';
  render();
  await new Promise((resolve) => setTimeout(resolve, 700));
  if (token !== state.taskToken) return;
  const authenticate = state.mode === 'offline' && !checkOnly && !state.externalOnline;
  if (authenticate) {
    state.phase = 'authenticating';
    render();
    await new Promise((resolve) => setTimeout(resolve, 700));
    if (token !== state.taskToken) return;
  }
  state.busy = false;
  if (state.mode === 'online' || state.externalOnline || (authenticate && state.authenticationSuccess)) {
    const hadFailures = state.failures > 0 || state.blocked;
    state.mode = 'online';
    state.externalOnline = false;
    state.authenticationSuccess = false;
    state.failures = 0;
    state.blocked = false;
    addEvent('success', authenticate ? '模拟认证完成，外网复查通过，网络已恢复' : hadFailures ? '外网已恢复，检查通过后清除失败保护；未提交认证' : '外网检查通过，未发送校园网认证请求');
    toast(authenticate ? '演示：认证后复查外网通过，网络已恢复。' : hadFailures ? '检测到外网恢复，已清除失败保护，没有提交认证。' : '外网可用，本次没有提交校园网认证。');
    $('#scenario').value = 'online';
  } else if (checkOnly) {
    addEvent('info', '外网仍不可用；失败保护中，未发送认证请求');
    toast('仅检查外网。保护仍然生效，没有新的认证尝试。');
  } else {
    state.failures = Math.min(state.failures + 1, 3);
    state.blocked = state.failures === 3;
    addEvent('warning', state.blocked ? '连续 3 轮未恢复网络，已暂停校园网认证' : `第 ${state.failures} 轮认证未恢复外网，已计入失败保护`);
    toast(state.blocked ? '三轮失败保护已触发。等待或重启不会自动解除。' : `演示：本轮未恢复网络，当前 ${state.failures} / 3 轮失败。`);
  }
  render();
}

function toggleMonitor() {
  if (state.busy) return;
  if (!state.monitor && state.migrationPending) { showWizard(2); return; }
  state.monitor = !state.monitor;
  addEvent('info', state.monitor ? '自动守护已恢复；保留原有失败保护状态' : '用户暂停自动守护，周期检查停止');
  render();
  toast(state.monitor ? state.blocked ? '守护已开启，校园网认证仍受失败保护暂停。' : '自动守护已恢复。' : '自动守护已暂停，可随时手动检查。');
}
function modalBody(title, body, glyph = 'info') {
  return `<div class="modal-body"><div class="modal-icon">${icon(glyph)}</div><div class="modal-heading"><h2 id="modal-title">${title}</h2><button aria-label="关闭对话框" data-dismiss>${icon('close')}</button></div>${body}</div>`;
}
function modalFooter(html) { return `<div class="modal-footer">${html}</div>`; }
function showModal(html) {
  previousFocus = document.activeElement;
  dialogVersion += 1;
  $('#modal-content').innerHTML = html;
  if (!$('#modal').open) $('#modal').showModal();
  $$('[data-dismiss]').forEach((button) => { button.onclick = closeModal; });
}
function closeModal() {
  dialogVersion += 1;
  $('#modal').close();
  previousFocus?.focus();
}
function showProtection() {
  if (state.blocked) { resetProtection(); return; }
  showModal(`${modalBody('少一些请求，多一层保护。', '<p>10 分钟内连续 3 轮认证未恢复网络，就停止校园网认证。一次认证流程可能包含多个请求，因此“3 轮”并不等于 3 个 HTTP 请求。</p><div class="modal-note">保护触发后不会过 10 分钟自动重试。外网恢复会清除保护，也可在确认配置后手动重置；重启应用和开关守护不会解除保护。</div>', 'shield')}${modalFooter('<button class="button primary" data-dismiss>明白了</button>')}`);
}
function resetProtection() {
  if (state.busy) { toast('请等待当前检查结束，再重置失败保护。'); return; }
  showModal(`${modalBody('重置失败保护？', '<p>重置后会恢复认证尝试的额度。请先确认账号密码和目标 Wi-Fi 正确，避免重复触发保护。</p><div class="modal-note">这只清除失败记录，不立即登录。下一次检查仍先判断外网是否可用。</div>', 'shield')}${modalFooter('<button class="button secondary" data-dismiss>暂不重置</button><button class="button primary" id="confirm-reset">确认重置</button>')}`);
  $('#confirm-reset').onclick = () => {
    state.failures = 0;
    state.blocked = false;
    addEvent('info', '用户手动重置失败保护；未立即提交认证');
    render();
    closeModal();
    toast('失败记录已清除，下次检查仍先检测外网。');
  };
}
function showPermissionHelp() {
  showModal(`${modalBody('无法读取当前 Wi-Fi', '<p>部分 Windows 版本会限制无线网络信息访问。正式版会显示系统返回的具体原因，并提供对应的设置入口。</p><ol><li>打开 Windows“设置 → 隐私和安全性 → 位置”。</li><li>检查位置服务，以及桌面应用访问位置的相关选项。</li><li>返回助手重新检查；无需默认以管理员运行。</li></ol><div class="modal-note">本原型不会打开或修改系统权限。实际权限项以你的 Windows 版本为准。</div>', 'wifi')}${modalFooter('<button class="button primary" data-dismiss>知道了</button>')}`);
}
function showTray() {
  $('#tray-stage').hidden = false;
  render();
}
function hideTray() { $('#tray-stage').hidden = true; }

function showWizard(step = 0) {
  wizardStep = step;
  const headings = ['选择常用的校园 Wi-Fi', '保存校园网账号', '处理原有脚本任务', '准备好，让连接自动发生。'];
  let body = '';
  if (step === 0) body = '<p>助手只在目标网络上提交校园网认证。请先在 Windows 中手动连接一次并保存 Wi-Fi。</p><label style="margin-top:24px">已保存的 Wi-Fi<select id="wizard-wifi"><option>HUAWEI-Jie_5G</option><option>OUC-DEMO</option></select></label><div class="modal-note">Wi-Fi 密码继续交给 Windows 管理，助手只使用已保存的连接配置。</div>';
  if (step === 1) body = '<p>这是虚构配置演示，请勿输入真实账号密码。正式版由 Windows 当前用户加密保管凭据。</p><label style="margin-top:22px">校园网账号<input id="wizard-username" value="demo_student" autocomplete="off" maxlength="64"></label><label>校园网密码<input id="wizard-password" type="password" value="demo_only_123" autocomplete="off" maxlength="128"></label><p class="form-error" id="wizard-error" hidden>请填写演示账号和密码。</p>';
  if (step === 2) body = '<p>如果此前设置了任务计划，新桌面版会把重复检查统一交给内部守护。</p><div class="migration-item"><strong>发现 1 个旧任务（演示）</strong>任务：OUC_AutoLogin_Demo<br>操作：auto_login.py --once · 每 2 分钟重复<br>导入：网络配置 + 连续失败记录</div><label class="wizard-option"><input id="wizard-migrate" type="checkbox" checked><span>完成配置后，停用这个匹配的旧任务<small>正式版会先展示完整路径与任务名，不处理其他任务。</small></span></label>';
  if (step === 3) body = `<div class="wizard-success">${icon('check')}</div><p>${wizardData.migrate ? '配置已经就绪。首次检查仍先检测外网，有网时不提交校园网认证。' : '配置可以保存，旧任务尚未处理。新守护将保持暂停，避免两个入口并行认证。'}</p><label class="wizard-option"><input id="wizard-startup" type="checkbox"><span>登录 Windows 后自动运行<small>启动时收起到托盘，锁屏后继续守护。</small></span></label><div class="modal-note">导入会保留旧脚本的失败保护。开启守护不代表自动解除认证暂停。</div>`;
  showModal(`<div class="wizard-top"><img src="assets/app-icon.svg" width="38" height="38" alt=""><div><strong>欢迎使用海大网络助手</strong><span>首次设置 · 模拟流程</span></div></div><div class="modal-body wizard-body"><div class="wizard-step">STEP ${String(step + 1).padStart(2, '0')} / 04</div><div class="modal-heading"><h2 id="modal-title">${headings[step]}</h2></div>${body}</div>${modalFooter(`${step ? '<button class="button secondary left" id="wizard-back">上一步</button>' : '<button class="button text left" data-dismiss>稍后设置</button>'}<button class="button primary" id="wizard-next">${step === 3 ? '开启守护' : '下一步'}${icon('arrow')}</button>`)}`);
  if (step === 0) $('#wizard-wifi').value = wizardData.wifi;
  if (step === 1) { $('#wizard-username').value = wizardData.username; $('#wizard-password').value = wizardData.password; }
  if (step === 2) $('#wizard-migrate').checked = wizardData.migrate;
  if (step === 3) $('#wizard-startup').checked = wizardData.startup;
  if (step === 3 && !wizardData.migrate) $('#wizard-next').innerHTML = `保存，稍后处理${icon('arrow')}`;
  const rememberStep = () => {
    if (wizardStep === 0) wizardData.wifi = $('#wizard-wifi').value;
    if (wizardStep === 1) { wizardData.username = $('#wizard-username').value; wizardData.password = $('#wizard-password').value; }
    if (wizardStep === 2) wizardData.migrate = $('#wizard-migrate').checked;
    if (wizardStep === 3) wizardData.startup = $('#wizard-startup').checked;
  };
  if ($('#wizard-back')) $('#wizard-back').onclick = () => { rememberStep(); showWizard(wizardStep - 1); };
  $('#wizard-next').onclick = () => {
    if (wizardStep === 1 && (!$('#wizard-username').value.trim() || !$('#wizard-password').value)) { $('#wizard-error').hidden = false; return; }
    rememberStep();
    if (wizardStep < 3) { showWizard(wizardStep + 1); return; }
    setSwitch($('[data-setting="startup"]'), wizardData.startup);
    $('#target-wifi').value = wizardData.wifi;
    $('#username').value = wizardData.username;
    $('#password').value = wizardData.password;
    state.monitor = wizardData.migrate;
    state.migrationPending = !wizardData.migrate;
    render();
    closeModal();
    go('overview');
    addEvent('info', '首次设置演示完成，已保留当前失败保护状态');
    toast(wizardData.migrate ? '演示配置已带入主界面；旧任务迁移仅作展示，未改动系统。' : '配置已带入主界面，新守护保持暂停，等待处理旧任务。');
  };
}

function showInstaller(step = 0) {
  installStep = step;
  let title, body, footer;
  if (step === 0) {
    title = '为连接，留一份安心。';
    body = '<p>海大网络助手把校园网自动连接放进安静的桌面体验。无需手动安装 Python，无需配置任务计划。</p><div class="migration-item"><strong>安装到当前 Windows 用户</strong>默认目录：%LOCALAPPDATA%\\Programs\\OUCNetworkAssistant<br>配置与日志保存在独立用户数据目录。</div><div class="modal-note">本次交付是安装流程设计，并不是可执行安装包。这里的所有操作均为模拟。</div>';
    footer = '<button class="button text left" data-dismiss>取消</button><button class="button primary" id="install-next">开始模拟安装</button>';
  } else if (step === 1) {
    title = '正在准备你的网络助手';
    body = '<p>打包 Python 核心、桌面界面与依赖，普通用户即可使用。</p><div class="installation-progress"><i></i></div><p>演示中：正在安装桌面组件…</p><div class="modal-note">正式安装器会处理运行中实例，升级时保留配置与失败保护，不自动覆盖原脚本。</div>';
    footer = '<button class="button secondary" data-dismiss>关闭演示</button>';
  } else {
    title = '安装完成。连接即将更简单。';
    body = `<div class="wizard-success">${icon('check')}</div><p>接下来只需选择网络、配置账号，并处理已有任务。默认不会静默开启系统自启。</p><label class="wizard-option"><input type="checkbox" id="install-launch" checked><span>启动助手并完成首次设置</span></label><div class="modal-note">个人工具 · 非中国海洋大学官方软件</div>`;
    footer = '<button class="button primary" id="install-finish">完成</button>';
  }
  showModal(`<div class="wizard-top"><img src="assets/app-icon.svg" width="38" height="38" alt=""><div><strong>海大网络助手 Setup</strong><span>Windows 安装体验 · 模拟流程</span></div></div><div class="modal-body wizard-body"><div class="modal-heading"><h2 id="modal-title">${title}</h2></div>${body}</div><div class="install-steps"><span class="${step === 0 ? 'current' : ''}">01 准备</span><span class="${step === 1 ? 'current' : ''}">02 安装</span><span class="${step === 2 ? 'current' : ''}">03 完成</span></div>${modalFooter(footer)}`);
  if ($('#install-next')) $('#install-next').onclick = () => showInstaller(1);
  if (step === 1) {
    const version = dialogVersion;
    setTimeout(() => { if ($('#modal').open && dialogVersion === version) showInstaller(2); }, 1700);
  }
  if ($('#install-finish')) $('#install-finish').onclick = () => {
    const launch = $('#install-launch').checked;
    closeModal();
    if (launch) showWizard(); else toast('安装体验演示完成，未安装软件。');
  };
}

$$('[data-page]').forEach((button) => { button.onclick = () => go(button.dataset.page); });
$$('[data-go]').forEach((button) => { button.onclick = () => go(button.dataset.go); });
$('#scenario').onchange = (event) => selectScenario(event.target.value);
$('#simulate-recovery').onclick = () => { state.externalOnline = true; go('overview'); checkNetwork(); };
$('#check-network').onclick = checkNetwork;
$('#monitor-toggle').onclick = toggleMonitor;
$('#hero-secondary').onclick = () => state.blocked ? resetProtection() : go('activity');
$('#protection-details').onclick = showProtection;
$('#reset-protection').onclick = resetProtection;
$('#log-filter').onchange = renderEvents;
$('#show-password').onclick = () => {
  const show = $('#password').type === 'password';
  $('#password').type = show ? 'text' : 'password';
  $('#show-password').setAttribute('aria-label', show ? '隐藏演示密码' : '显示演示密码');
};
$('#account-form').onsubmit = (event) => {
  event.preventDefault();
  $('#account-save-state').textContent = '演示配置已更新，仅存在于当前页面。';
  addEvent('info', '演示配置已保存；失败保护状态保持不变');
  toast('仅演示保存效果。没有存储凭据，也没有向服务器发送数据。');
};
$('#target-wifi').onchange = () => { render(); toast('目标网络已在原型中更新，不会切换真实 Wi-Fi。'); };
$$('[data-setting]').forEach((button) => {
  button.onclick = () => {
    const enabled = button.getAttribute('aria-checked') !== 'true';
    setSwitch(button, enabled);
    if (button.dataset.setting === 'tray') state.tray = enabled;
    toast('偏好已在原型中更新，不会修改 Windows 设置。');
  };
});
$('#interval').onchange = (event) => {
  state.interval = Number(event.target.value);
  render();
  addEvent('info', `检查间隔演示改为 ${state.interval} 分钟`);
  toast('仅演示间隔设置，此页面不会定时发送真实请求。');
};
$('#export-log').onclick = () => {
  const content = '海大网络助手 — 原型模拟记录（不含真实联网行为）\r\n' + events.map((event) => `${event.time} [${event.type}] ${event.text}`).join('\r\n');
  const url = URL.createObjectURL(new Blob(['\ufeff' + content], { type: 'text/plain;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = 'ouc-network-demo-log.txt'; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('已导出演示记录，不包含账号或密码。');
};
$('#open-tray').onclick = showTray;
$('#minimize').onclick = showTray;
$('#close-window').onclick = () => {
  if (state.tray) showTray();
  else showModal(`${modalBody('退出网络助手？', '<p>关闭到托盘已关闭。正式版退出后将停止自动检查，失败保护记录仍会保留。</p>', 'pause')}${modalFooter('<button class="button secondary" data-dismiss>继续运行</button><button class="button primary" id="confirm-exit">模拟退出</button>')}`), bindExit();
};
function bindExit() {
  if ($('#confirm-exit')) $('#confirm-exit').onclick = () => {
    state.taskToken += 1;
    state.busy = false;
    state.monitor = false;
    render();
    closeModal(); hideTray();
    toast('演示已退出：自动守护停止，失败保护记录保留。');
  };
}
$('#maximize').onclick = () => toast('窗口设计参考：默认 1180 × 814，正式版支持缩放与高 DPI。');
$('#return-window').onclick = hideTray;
$('#tray-open').onclick = hideTray;
$('#tray-check').onclick = () => { hideTray(); go('overview'); checkNetwork(); };
$('#tray-pause').onclick = toggleMonitor;
$('#tray-exit').onclick = () => {
  showModal(`${modalBody('退出网络助手？', '<p>退出后不再自动检查网络。正式版会保存当前配置与失败保护，下次打开后继续。</p>', 'pause')}${modalFooter('<button class="button secondary" data-dismiss>继续守护</button><button class="button primary" id="confirm-exit">模拟退出</button>')}`);
  bindExit();
};
$('#preview-setup').onclick = () => showWizard();
$('#preview-install').onclick = () => showInstaller();
$('#modal').addEventListener('cancel', () => { dialogVersion += 1; });
$('#modal').addEventListener('click', (event) => { if (event.target === $('#modal')) closeModal(); });
render();
renderEvents();
