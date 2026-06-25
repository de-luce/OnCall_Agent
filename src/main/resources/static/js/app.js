const API = '/api';

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let sessionId = crypto.randomUUID();
let chatBusy = false;
let chatAbortController = null;
let activeChatStream = null;

function setChatBusy(busy) {
    chatBusy = busy;
    const sendBtn = $('#chatSendBtn');
    sendBtn.textContent = busy ? '停止' : '发送';
    sendBtn.classList.toggle('btn-stop', busy);
    sendBtn.setAttribute('aria-label', busy ? '停止生成' : '发送消息');
    if (!busy) {
        chatAbortController = null;
        activeChatStream = null;
    }
}

function stopChatGeneration() {
    chatAbortController?.abort();
    if (activeChatStream && !activeChatStream.finalized) {
        activeChatStream.finalized = true;
        activeChatStream.displayFinalized = true;
        activeChatStream.cancel?.();
        if (activeChatStream.view && activeChatStream.state) {
            finalizeAssistantMessage(activeChatStream.view, {
                ...activeChatStream.state,
                aborted: true,
            });
        } else if (activeChatStream.assistantEl) {
            activeChatStream.assistantEl.classList.remove('loading');
            activeChatStream.assistantEl.textContent = '（已停止生成）';
        }
    }
    setChatBusy(false);
}

function getSessionId() {
    const input = $('#sessionId').value.trim();
    return input || sessionId;
}

function setSessionId(id) {
    sessionId = id;
    $('#sessionId').value = id;
}

async function apiGet(path) {
    console.log('[发送]', path);
    const res = await fetch(`${API}${path}`);
    const json = await res.json();
    console.log('[接收]', path, res.status, json);
    if (json.code !== 0) {
        throw new Error(json.message || '请求失败');
    }
    return json.data;
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

async function apiDelete(path) {
    console.log('[发送] DELETE', path);
    const res = await fetch(`${API}${path}`, { method: 'DELETE' });
    const json = await res.json();
    console.log('[接收] DELETE', path, res.status, json);
    if (json.code !== 0) {
        throw new Error(json.message || '请求失败');
    }
    return json.data;
}

function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
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
    scrollChatToBottom();
    return el;
}

function appendAssistantStreamMessage() {
    const box = $('#chatMessages');
    const el = document.createElement('div');
    el.className = 'message assistant loading';

    const thinkingStatus = document.createElement('div');
    thinkingStatus.className = 'thinking-status';
    thinkingStatus.textContent = '思考中';

    const thinking = document.createElement('div');
    thinking.className = 'thinking-block hidden';

    const thinkingText = document.createElement('div');
    thinkingText.className = 'thinking-text';
    thinking.appendChild(thinkingText);

    const answer = document.createElement('div');
    answer.className = 'answer-block';

    el.appendChild(thinkingStatus);
    el.appendChild(thinking);
    el.appendChild(answer);
    box.appendChild(el);
    scrollChatToBottom();

    return {
        el,
        thinkingStatusEl: thinkingStatus,
        thinkingEl: thinking,
        thinkingTextEl: thinkingText,
        answerEl: answer,
    };
}

const SCROLL_STICK_THRESHOLD = 24;

function isAtScrollBottom(el) {
    if (!el) return true;
    return el.scrollHeight - el.clientHeight <= SCROLL_STICK_THRESHOLD
        || el.scrollHeight - el.scrollTop - el.clientHeight <= SCROLL_STICK_THRESHOLD;
}

function scrollToBottomIfNeeded(el) {
    if (el && isAtScrollBottom(el)) {
        el.scrollTop = el.scrollHeight;
    }
}

function scrollAnswerToBottom(view) {
    scrollToBottomIfNeeded(view?.answerEl);
}

function scrollThinkingToBottom(view) {
    scrollToBottomIfNeeded(view?.thinkingEl);
}

function scrollChatToBottom() {
    const box = $('#chatMessages');
    if (box) {
        box.scrollTop = box.scrollHeight;
    }
}

function scrollChatToBottomIfNeeded() {
    scrollToBottomIfNeeded($('#chatMessages'));
}

function* parseJsonObjects(text) {
    let i = 0;
    while (i < text.length) {
        while (i < text.length && /\s/.test(text[i])) {
            i += 1;
        }
        if (i >= text.length || text[i] !== '{') {
            break;
        }

        let depth = 0;
        let inString = false;
        let escaped = false;
        const start = i;

        for (; i < text.length; i += 1) {
            const ch = text[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch === '\\') {
                    escaped = true;
                } else if (ch === '"') {
                    inString = false;
                }
                continue;
            }
            if (ch === '"') {
                inString = true;
            } else if (ch === '{') {
                depth += 1;
            } else if (ch === '}') {
                depth -= 1;
                if (depth === 0) {
                    try {
                        yield JSON.parse(text.slice(start, i + 1));
                    } catch (err) {
                        console.warn('[SSE JSON 解析失败]', err);
                    }
                    i += 1;
                    break;
                }
            }
        }

        if (depth !== 0) {
            break;
        }
    }
}

function extractSsePayload(block) {
    const lines = block.split('\n');
    const dataLines = [];
    for (const line of lines) {
        if (!line) continue;
        if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart());
        } else if (!line.startsWith(':') && !line.startsWith('event:') && !line.startsWith('id:')) {
            dataLines.push(line.trim());
        }
    }
    return dataLines.join('');
}

function applyStreamEvent(view, event, state) {
    if (!event || !event.type) return;

    if (event.type === 'reasoning' && typeof event.text === 'string') {
        state.reasoningText += event.text;
        view.thinkingTextEl.textContent = state.reasoningText;
        view.thinkingEl.classList.remove('hidden');
        scrollThinkingToBottom(view);
        return;
    }

    if (event.type === 'content' && typeof event.text === 'string') {
        const answerEl = view.answerEl;
        const stickAnswer = isAtScrollBottom(answerEl);
        const stickChat = isAtScrollBottom($('#chatMessages'));
        state.answerText += event.text;
        view.answerEl.textContent = state.answerText.replace(/^\n+/, '');
        view.thinkingStatusEl.classList.add('hidden');
        view.el.classList.remove('loading');
        if (stickAnswer) {
            answerEl.scrollTop = answerEl.scrollHeight;
        }
        if (stickChat) {
            scrollChatToBottom();
        }
        return;
    }

    if (event.type === 'done' && typeof event.answer === 'string') {
        state.doneAnswer = event.answer;
    }
}

function processStreamBuffer(buffer, view, state) {
    const blocks = buffer.split('\n\n');
    const remainder = blocks.pop() ?? '';

    for (const block of blocks) {
        const payload = extractSsePayload(block);
        if (!payload) continue;
        for (const event of parseJsonObjects(payload)) {
            applyStreamEvent(view, event, state);
        }
    }

    return remainder;
}

function flushStreamBuffer(buffer, view, state) {
    const payload = extractSsePayload(buffer);
    if (!payload) return;
    for (const event of parseJsonObjects(payload)) {
        applyStreamEvent(view, event, state);
    }
}

function finalizeAssistantMessage(view, { answerText, reasoningText, doneAnswer = '', aborted = false }) {
    view.el.classList.remove('loading');
    view.thinkingStatusEl.classList.add('hidden');
    view.thinkingEl.classList.add('hidden');

    let finalAnswer = answerText.trim();
    if (!finalAnswer && typeof doneAnswer === 'string') {
        finalAnswer = doneAnswer.trim();
    }
    if (!finalAnswer && !aborted && reasoningText.trim()) {
        finalAnswer = reasoningText.trim();
    }
    if (aborted) {
        finalAnswer = finalAnswer
            ? `${finalAnswer}\n\n（已停止生成）`
            : '（已停止生成）';
    }
    if (!finalAnswer) {
        finalAnswer = '（无响应内容，请查看后端日志是否已调用 LLM）';
    }

    view.answerEl.textContent = finalAnswer.replace(/^\n+/, '');
    scrollToBottomIfNeeded(view.answerEl);
    scrollChatToBottomIfNeeded();
    return finalAnswer;
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

async function streamChat(message, signal) {
    const payload = { message, session_id: getSessionId() };
    console.log('[发送] /api/chat_stream', payload);

    const view = appendAssistantStreamMessage();
    const state = {
        answerText: '',
        reasoningText: '',
        doneAnswer: '',
    };

    const streamControl = {
        finalized: false,
        view,
        state,
        cancel() {},
    };
    activeChatStream = streamControl;

    const onAbort = () => {
        if (!streamControl.finalized) {
            streamControl.finalized = true;
            streamControl.cancel();
        }
    };
    signal.addEventListener('abort', onAbort);

    try {
        const res = await fetch(`${API}/chat_stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
            signal,
        });

        console.log('[接收] /api/chat_stream 响应头', res.status, res.headers.get('content-type'));

        if (!res.ok) {
            const text = await res.text();
            console.error('[流式失败]', res.status, text);
            streamControl.finalized = true;
            view.thinkingStatusEl.classList.add('hidden');
            view.el.classList.remove('loading');
            view.answerEl.textContent = `错误：HTTP ${res.status}`;
            throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`);
        }

        const reader = res.body.getReader();
        streamControl.cancel = () => reader.cancel().catch(() => {});

        if (signal.aborted || streamControl.finalized) {
            if (!streamControl.displayFinalized) {
                streamControl.displayFinalized = true;
                finalizeAssistantMessage(view, { ...state, aborted: true });
            }
            return state.answerText || state.doneAnswer || '';
        }

        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            if (signal.aborted || streamControl.finalized) {
                break;
            }

            let readResult;
            try {
                readResult = await reader.read();
            } catch (err) {
                if (signal.aborted || streamControl.finalized || err.name === 'AbortError') {
                    break;
                }
                throw err;
            }

            const { done, value } = readResult;
            if (done) {
                flushStreamBuffer(buffer, view, state);
                console.log('[流式完成] 回答长度=', state.answerText.length, '思考长度=', state.reasoningText.length);
                break;
            }

            buffer += decoder.decode(value, { stream: true });
            buffer = processStreamBuffer(buffer, view, state);
        }

        if (streamControl.finalized || signal.aborted) {
            if (!streamControl.displayFinalized) {
                streamControl.displayFinalized = true;
                finalizeAssistantMessage(view, { ...state, aborted: true });
            }
            return state.answerText || state.doneAnswer || '';
        }

        streamControl.finalized = true;
        const finalAnswer = finalizeAssistantMessage(view, state);
        if (!state.answerText && !state.reasoningText && !state.doneAnswer) {
            console.warn('[流式警告] 未收到任何有效事件');
        }
        return finalAnswer;
    } catch (err) {
        if (err.name === 'AbortError' || signal.aborted) {
            if (!streamControl.displayFinalized) {
                streamControl.displayFinalized = true;
                finalizeAssistantMessage(view, { ...state, aborted: true });
            }
            throw err;
        }
        if (!streamControl.finalized) {
            view.thinkingStatusEl.classList.add('hidden');
            view.el.classList.remove('loading');
            if (!view.answerEl.textContent) {
                view.answerEl.textContent = `错误：${err.message}`;
            }
        }
        throw err;
    } finally {
        signal.removeEventListener('abort', onAbort);
        if (activeChatStream === streamControl) {
            activeChatStream = null;
        }
    }
}

async function sendChat(message, signal) {
    const view = appendAssistantStreamMessage();
    activeChatStream = { finalized: false, view, state: { answerText: '', reasoningText: '', doneAnswer: '' } };

    const res = await fetch(`${API}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, session_id: getSessionId() }),
        signal,
    });

    if (signal.aborted) {
        view.thinkingStatusEl.classList.add('hidden');
        view.el.classList.remove('loading');
        view.answerEl.textContent = '（已停止生成）';
        throw new DOMException('Aborted', 'AbortError');
    }

    const json = await res.json();
    console.log('[接收] /chat', res.status, json);
    if (json.code !== 0) {
        view.thinkingStatusEl.classList.add('hidden');
        view.el.classList.remove('loading');
        throw new Error(json.message || '请求失败');
    }
    const data = json.data;
    if (data.session_id) {
        setSessionId(data.session_id);
    }
    view.thinkingStatusEl.classList.add('hidden');
    view.el.classList.remove('loading');
    view.answerEl.textContent = data.answer;
    if (activeChatStream?.view === view) {
        activeChatStream = null;
    }
}

async function handleChatSubmit(e) {
    e.preventDefault();

    if (chatBusy) {
        stopChatGeneration();
        return;
    }

    const input = $('#chatInput');
    const message = input.value.trim();
    if (!message) return;

    const useStream = $('#streamToggle').checked;
    chatAbortController = new AbortController();
    setChatBusy(true);
    appendMessage('user', message);
    input.value = '';

    console.log('[用户发送]', { message, sessionId: getSessionId(), stream: useStream });

    try {
        if (useStream) {
            await streamChat(message, chatAbortController.signal);
        } else {
            await sendChat(message, chatAbortController.signal);
        }
    } catch (err) {
        if (err.name === 'AbortError') {
            console.log('[对话已停止]');
            return;
        }
        console.error('[对话错误]', err);
        appendMessage('system', `错误：${err.message}`);
        showToast(err.message, 'error');
    } finally {
        setChatBusy(false);
        input.focus();
        await loadHistorySessions(getSessionId());
    }
}

let keywordToggleSync = null;

function bindKeywordToggle(keywordList, toggleBtn) {
    if (!keywordList || !toggleBtn) return;

    const syncToggle = () => {
        if (toggleBtn.dataset.expanded === 'true') return;
        keywordList.classList.add('is-collapsed');
        keywordList.classList.remove('is-expanded');
        const overflows = keywordList.scrollHeight > keywordList.clientHeight + 1;
        toggleBtn.classList.toggle('hidden', !overflows);
        toggleBtn.textContent = '展开全部';
    };

    toggleBtn.onclick = () => {
        const expanded = toggleBtn.dataset.expanded === 'true';
        if (expanded) {
            toggleBtn.dataset.expanded = 'false';
            syncToggle();
        } else {
            toggleBtn.dataset.expanded = 'true';
            keywordList.classList.remove('is-collapsed');
            keywordList.classList.add('is-expanded');
            toggleBtn.textContent = '收起';
            toggleBtn.classList.remove('hidden');
        }
    };

    toggleBtn.dataset.expanded = 'false';
    keywordToggleSync = syncToggle;
    syncToggle();
}

if (!window.__keywordToggleResizeBound) {
    window.__keywordToggleResizeBound = true;
    window.addEventListener('resize', () => keywordToggleSync?.());
}

function formatHistoryTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    if (isToday) {
        return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    }
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
}

function clearChatMessages() {
    $('#chatMessages').innerHTML = '';
}

function renderHistoryMessages(messages) {
    clearChatMessages();
    if (!messages || messages.length === 0) {
        appendMessage('system', '该会话暂无消息，开始提问吧。');
        return;
    }
    messages.forEach((item) => {
        appendMessage(item.role === 'user' ? 'user' : 'assistant', item.content);
    });
}

function markActiveHistory(sessionId) {
    $$('.history-item').forEach((el) => {
        el.classList.toggle('active', el.dataset.sessionId === sessionId);
    });
}

async function loadHistorySessions(activeSessionId = getSessionId()) {
    const list = $('#historyList');
    if (!list) return;

    try {
        const data = await apiGet('/history/sessions?limit=50');
        list.innerHTML = '';
        const sessions = data.sessions || [];
        if (sessions.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'history-empty';
            empty.textContent = '暂无历史会话';
            list.appendChild(empty);
            return;
        }

        sessions.forEach((session) => {
            const row = document.createElement('div');
            row.className = 'history-item-row';

            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'history-item';
            btn.dataset.sessionId = session.session_id;
            btn.innerHTML = `
                <div class="history-item-title">${escapeHtml(session.title || '新会话')}</div>
                <div class="history-item-meta">${session.message_count} 条 · ${formatHistoryTime(session.updated_at)}</div>
            `;
            btn.addEventListener('click', () => openHistorySession(session.session_id));

            const deleteBtn = document.createElement('button');
            deleteBtn.type = 'button';
            deleteBtn.className = 'history-item-delete';
            deleteBtn.title = '删除会话';
            deleteBtn.setAttribute('aria-label', '删除会话');
            deleteBtn.textContent = '×';
            deleteBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                deleteHistorySession(session.session_id, session.title);
            });

            row.appendChild(btn);
            row.appendChild(deleteBtn);
            list.appendChild(row);
        });
        markActiveHistory(activeSessionId);
    } catch (err) {
        list.innerHTML = '';
        const empty = document.createElement('div');
        empty.className = 'history-empty';
        empty.textContent = '历史加载失败';
        list.appendChild(empty);
    }
}

async function openHistorySession(sessionId) {
    if (chatBusy) {
        showToast('请等待当前回复完成', 'error');
        return;
    }
    try {
        const data = await apiGet(`/history/sessions/${sessionId}/messages`);
        setSessionId(data.session_id);
        renderHistoryMessages(data.messages);
        markActiveHistory(data.session_id);
    } catch (err) {
        showToast(err.message, 'error');
    }
}

async function deleteHistorySession(sessionId, title) {
    if (chatBusy) {
        showToast('请等待当前回复完成', 'error');
        return;
    }
    const label = (title || '该会话').trim();
    if (!confirm(`确定删除「${label}」？此操作不可恢复。`)) {
        return;
    }
    try {
        await apiDelete(`/history/sessions/${sessionId}`);
        if (getSessionId() === sessionId) {
            setSessionId(crypto.randomUUID());
            clearChatMessages();
            appendMessage('system', '会话已删除，请输入运维相关问题。');
        }
        await loadHistorySessions(getSessionId());
        showToast('会话已删除');
    } catch (err) {
        showToast(err.message, 'error');
    }
}

function startNewSession() {
    if (chatBusy) {
        showToast('请等待当前回复完成', 'error');
        return;
    }
    setSessionId(crypto.randomUUID());
    clearChatMessages();
    appendMessage('system', '已创建新会话，请输入运维相关问题。');
    markActiveHistory(getSessionId());
    showToast('新会话已创建');
}

async function loadKnowledgeKeywords() {
    const meta = $('#keywordMeta');
    const keywordList = $('#keywordList');
    const keywordToggle = $('#keywordToggle');
    const documentList = $('#documentList');

    try {
        const data = await apiGet('/knowledge/keywords');
        meta.textContent = `共 ${data.documents.length} 个文档 · ${data.keywords.length} 个关键词`;

        keywordList.innerHTML = '';
        keywordToggle.classList.add('hidden');
        if (!data.keywords || data.keywords.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'keyword-empty';
            empty.textContent = '暂无关键词，请先上传 SOP 文档';
            keywordList.appendChild(empty);
        } else {
            data.keywords.forEach((keyword) => {
                const tag = document.createElement('button');
                tag.type = 'button';
                tag.className = 'keyword-tag';
                tag.textContent = keyword;
                tag.addEventListener('click', () => {
                    $('#knowledgeInput').value = keyword;
                    $('#knowledgeInput').focus();
                });
                keywordList.appendChild(tag);
            });
            requestAnimationFrame(() => bindKeywordToggle(keywordList, keywordToggle));
        }

        documentList.innerHTML = '';
        if (data.documents && data.documents.length > 0) {
            documentList.classList.remove('hidden');
            const title = document.createElement('h4');
            title.textContent = '已入库文档';
            documentList.appendChild(title);

            data.documents.forEach((doc) => {
                const item = document.createElement('div');
                item.className = 'document-item';
                const chunkText = doc.chunkCount > 0 ? `${doc.chunkCount} 个分片` : '待重新向量化';
                item.innerHTML = `<span>${doc.displayName}</span><span>${chunkText}</span>`;
                documentList.appendChild(item);
            });
        } else {
            documentList.classList.add('hidden');
        }
    } catch (err) {
        meta.textContent = '关键词加载失败';
        keywordList.innerHTML = '';
        const empty = document.createElement('div');
        empty.className = 'keyword-empty';
        empty.textContent = err.message;
        keywordList.appendChild(empty);
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
        await loadKnowledgeKeywords();
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
        const data = await apiPost('/knowledge/chat', { message, session_id: getSessionId() });
        answerBox.textContent = data.answer;
        if (data.session_id) setSessionId(data.session_id);
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
            alert_message: alertMessage,
            service_name: serviceName || undefined,
            session_id: getSessionId(),
        });

        if (data.session_id) setSessionId(data.session_id);

        $('#opsRootCause').textContent = data.root_cause || '—';
        $('#opsRecommendation').textContent = data.recommendation || '—';

        const steps = $('#opsSteps');
        steps.innerHTML = '';
        (data.executed_steps || []).forEach((step) => {
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
            if (tab === 'knowledge') {
                loadKnowledgeKeywords();
            } else if (tab === 'chat') {
                loadHistorySessions();
            }
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
    loadKnowledgeKeywords();
    loadHistorySessions();

    $('#chatForm').addEventListener('submit', handleChatSubmit);
    $('#knowledgeForm').addEventListener('submit', handleKnowledgeSubmit);
    $('#opsForm').addEventListener('submit', handleOpsSubmit);
    $('#newSessionBtn').addEventListener('click', startNewSession);

    appendMessage('system', '欢迎使用 OnCall Agent，请输入运维相关问题。');
}

document.addEventListener('DOMContentLoaded', init);
