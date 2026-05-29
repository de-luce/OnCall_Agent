const API = '/api';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let sessionId = crypto.randomUUID();

function getSessionId() {
    const input = $('#sessionId').value.trim();
    return input || sessionId;
}

function setSessionId(id) {
    sessionId = id;
    $('#sessionId').value = id;
}

async function apiPost(path, body) {
    console.log('[发送]', path, body);
    const res = await fetch(`${API}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const json = await res.json();
    console.log('[接收]', path, res.status, json);
    if (json.code !== 0) {
        throw new Error(json.message || '请求失败');
    }
    return json.data;
}

function showToast(message, type = 'success') {
    const container = $('#toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 3500);
}

function appendMessage(role, text, extraClass = '') {
    const box = $('#chatMessages');
    const el = document.createElement('div');
    el.className = `message ${role} ${extraClass}`.trim();
    el.textContent = text;
    box.appendChild(el);
    box.scrollTop = box.scrollHeight;
    return el;
}

async function checkHealth() {
    const status = $('#healthStatus');
    try {
        const res = await fetch(`${API}/health`);
        const json = await res.json();
        if (json.code === 0) {
            status.className = 'status online';
            status.querySelector('span:last-child').textContent = '服务正常';
        } else {
            throw new Error();
        }
    } catch {
        status.className = 'status offline';
        status.querySelector('span:last-child').textContent = '服务离线';
    }
}

async function streamChat(message) {
    const payload = { message, sessionId: getSessionId() };
    console.log('[发送] /api/chat_stream', payload);

    const res = await fetch(`${API}/chat_stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });

    console.log('[接收] /api/chat_stream 响应头', res.status, res.headers.get('content-type'));

    if (!res.ok) {
        const text = await res.text();
        console.error('[流式失败]', res.status, text);
        throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`);
    }

    const assistantEl = appendMessage('assistant', '', 'loading');
    let fullText = '';

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
        const { done, value } = await reader.read();
        if (done) {
            console.log('[流式完成] 总长度=', fullText.length);
            break;
        }

        const raw = decoder.decode(value, { stream: true });
        console.debug('[流式原始数据]', raw);
        buffer += raw;
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
            if (line.startsWith('data:')) {
                const data = line.slice(5).trim();
                if (data && data !== '[DONE]') {
                    fullText += data;
                    assistantEl.textContent = fullText;
                    assistantEl.classList.remove('loading');
                    $('#chatMessages').scrollTop = $('#chatMessages').scrollHeight;
                }
            }
        }
    }

    assistantEl.classList.remove('loading');
    if (!fullText) {
        assistantEl.textContent = '（无响应内容，请查看后端日志是否已调用 LLM）';
        console.warn('[流式警告] 未收到任何 data 片段');
    }
}

async function sendChat(message) {
    const data = await apiPost('/chat', { message, sessionId: getSessionId() });
    if (data.sessionId) {
        setSessionId(data.sessionId);
    }
    appendMessage('assistant', data.answer);
}

async function handleChatSubmit(e) {
    e.preventDefault();
    const input = $('#chatInput');
    const message = input.value.trim();
    if (!message) return;

    const sendBtn = $('#chatSendBtn');
    const useStream = $('#streamToggle').checked;
    sendBtn.disabled = true;
    appendMessage('user', message);
    input.value = '';

    console.log('[用户发送]', { message, sessionId: getSessionId(), stream: useStream });

    try {
        if (useStream) {
            await streamChat(message);
        } else {
            await sendChat(message);
        }
    } catch (err) {
        console.error('[对话错误]', err);
        appendMessage('system', `错误：${err.message}`);
        showToast(err.message, 'error');
    } finally {
        sendBtn.disabled = false;
        input.focus();
    }
}

async function uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);

    const res = await fetch(`${API}/upload_file`, {
        method: 'POST',
        body: formData,
    });
    const json = await res.json();
    if (json.code !== 0) {
        throw new Error(json.message || '上传失败');
    }
    return json.data;
}

function setupUpload() {
    const zone = $('#uploadZone');
    const input = $('#fileInput');

    $('#pickFileBtn').addEventListener('click', () => input.click());
    zone.addEventListener('click', (e) => {
        if (e.target.id !== 'pickFileBtn') input.click();
    });

    zone.addEventListener('dragover', (e) => {
        e.preventDefault();
        zone.classList.add('dragover');
    });
    zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
    zone.addEventListener('drop', (e) => {
        e.preventDefault();
        zone.classList.remove('dragover');
        if (e.dataTransfer.files.length) {
            handleFile(e.dataTransfer.files[0]);
        }
    });

    input.addEventListener('change', () => {
        if (input.files.length) handleFile(input.files[0]);
    });
}

async function handleFile(file) {
    const result = $('#uploadResult');
    result.classList.remove('hidden');
    result.textContent = `上传中：${file.name}...`;

    try {
        const data = await uploadFile(file);
        result.textContent = `✓ ${data.message}（${data.fileName}，${data.chunkCount} 个分片）`;
        showToast('文档上传成功');
    } catch (err) {
        result.textContent = `✗ ${err.message}`;
        showToast(err.message, 'error');
    }
}

async function handleKnowledgeSubmit(e) {
    e.preventDefault();
    const input = $('#knowledgeInput');
    const message = input.value.trim();
    if (!message) return;

    const answerBox = $('#knowledgeAnswer');
    answerBox.classList.remove('hidden');
    answerBox.textContent = '思考中...';

    try {
        const data = await apiPost('/knowledge/chat', { message, sessionId: getSessionId() });
        answerBox.textContent = data.answer;
        if (data.sessionId) setSessionId(data.sessionId);
    } catch (err) {
        answerBox.textContent = `错误：${err.message}`;
        showToast(err.message, 'error');
    }
}

async function handleOpsSubmit(e) {
    e.preventDefault();
    const alertMessage = $('#alertMessage').value.trim();
    const serviceName = $('#serviceName').value.trim();
    if (!alertMessage) {
        showToast('请填写告警信息', 'error');
        return;
    }

    const btn = $('#opsSubmitBtn');
    btn.disabled = true;
    btn.textContent = '排查中...';

    const result = $('#opsResult');
    result.classList.add('hidden');

    try {
        const data = await apiPost('/ai_ops', {
            alertMessage,
            serviceName: serviceName || undefined,
            sessionId: getSessionId(),
        });

        if (data.sessionId) setSessionId(data.sessionId);

        $('#opsRootCause').textContent = data.rootCause || '—';
        $('#opsRecommendation').textContent = data.recommendation || '—';

        const steps = $('#opsSteps');
        steps.innerHTML = '';
        (data.executedSteps || []).forEach((step) => {
            const li = document.createElement('li');
            li.textContent = step;
            steps.appendChild(li);
        });

        $('#opsReport').textContent = data.report || '—';
        result.classList.remove('hidden');
        showToast('排查完成');
    } catch (err) {
        showToast(err.message, 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = '开始排查';
    }
}

function setupTabs() {
    $$('.nav-item').forEach((btn) => {
        btn.addEventListener('click', () => {
            const tab = btn.dataset.tab;
            $$('.nav-item').forEach((b) => b.classList.remove('active'));
            $$('.panel').forEach((p) => p.classList.remove('active'));
            btn.classList.add('active');
            $(`#panel-${tab}`).classList.add('active');
        });
    });
}

function setupChatInput() {
    const textarea = $('#chatInput');
    textarea.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            $('#chatForm').requestSubmit();
        }
    });
}

function init() {
    setSessionId(sessionId);
    checkHealth();
    setInterval(checkHealth, 30000);

    setupTabs();
    setupUpload();
    setupChatInput();

    $('#chatForm').addEventListener('submit', handleChatSubmit);
    $('#knowledgeForm').addEventListener('submit', handleKnowledgeSubmit);
    $('#opsForm').addEventListener('submit', handleOpsSubmit);
    $('#newSessionBtn').addEventListener('click', () => {
        setSessionId(crypto.randomUUID());
        appendMessage('system', '已创建新会话');
        showToast('新会话已创建');
    });

    appendMessage('system', '欢迎使用 OnCall Agent，请输入运维相关问题。');
}

document.addEventListener('DOMContentLoaded', init);
