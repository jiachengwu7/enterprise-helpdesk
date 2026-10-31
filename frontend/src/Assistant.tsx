import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  Bot,
  Check,
  Download,
  FileText,
  MessagesSquare,
  Plus,
  Send,
  ShieldCheck,
  Sparkles,
  Ticket,
  Trash2,
  X,
} from "lucide-react";
import { api, downloadDocument, errorMessage, post } from "./api";
import type {
  Answer,
  Conversation,
  ConversationDetail,
  Source,
  SystemStatus,
  Ticket as TicketType,
} from "./types";
import { Badge, ErrorBox, Loading, Modal, formatDate, useLoad } from "./ui";
/**
 * 用途：管理问答历史、来源预览与工单确认。
 * 输入：openTicket：跳转到指定工单的回调。
 * 输出：知识助手页面 JSX；加载会话和模型配置状态。
 */
export default function Assistant({
  openTicket,
}: {
  openTicket: (id: string) => void;
}) {
  const list = useLoad<Conversation[]>(() => api("/conversations")),
    system = useLoad<SystemStatus>(() => api("/system/status"));
  const [selected, setSelected] = useState<string | null>(null),
    [detail, setDetail] = useState<ConversationDetail | null>(null),
    [question, setQuestion] = useState(""),
    [requestTicket, setRequestTicket] = useState(false),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(false),
    [error, setError] = useState(""),
    [source, setSource] = useState<Source | null>(null),
    [deleting, setDeleting] = useState<string | null>(null),
    [lastAnswer, setLastAnswer] = useState<Answer | null>(null),
    [createdTicket, setCreatedTicket] = useState<TicketType | null>(null);
  const bottom = useRef<HTMLDivElement>(null),
    version = useRef(0);
  useEffect(() => {
    bottom.current?.scrollIntoView?.({ behavior: "smooth" });
  }, [detail?.messages.length, busy]);
  /**
   * 用途：加载指定会话并忽略过时响应。
   * 输入：id：会话 ID。
   * 输出：Promise<void>；更新消息、草稿及加载状态。
   */
  const select = async (id: string) => {
    // 请求序号存在 useRef 中且不触发渲染；快速切换会话时，旧响应不能覆盖新会话。
    const call = ++version.current;
    setSelected(id);
    setDetail(null);
    setLoading(true);
    setError("");
    setLastAnswer(null);
    setCreatedTicket(null);
    try {
      const result = await api<ConversationDetail>(`/conversations/${id}`);
      if (call === version.current) setDetail(result);
    } catch (e) {
      if (call === version.current) setError(errorMessage(e));
    } finally {
      if (call === version.current) setLoading(false);
    }
  };
  /**
   * 用途：切换为空白会话并使旧加载响应失效。
   * 输入：无参数。
   * 输出：void；重置选中会话和输入状态，不删除历史。
   */
  const fresh = () => {
    version.current++;
    setSelected(null);
    setDetail(null);
    setQuestion("");
    setLastAnswer(null);
    setCreatedTicket(null);
    setError("");
    setLoading(false);
  };
  /**
   * 用途：发送问题，必要时先创建会话。
   * 输入：e：表单事件；问题与草稿开关来自状态。
   * 输出：Promise<void>；更新回答与历史，失败时尝试恢复待确认状态。
   */
  const send = async (e: FormEvent) => {
    e.preventDefault();
    if (!question.trim() || busy) return;
    setBusy(true);
    setError("");
    let id = selected;
    try {
      if (!id) {
        const c = await post<Conversation>("/conversations", {
          title: question.trim().slice(0, 60),
        });
        id = c.id;
        setSelected(id);
      }
      const answer = await post<Answer>(`/conversations/${id}/messages`, {
        // requestTicket 仅请求生成草稿；此时尚未创建工单，后续还要由用户确认。
        question: question.trim(),
        requestTicket,
      });
      setLastAnswer(answer);
      setQuestion("");
      setRequestTicket(false);
      setDetail(await api<ConversationDetail>(`/conversations/${id}`));
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
      if (id)
        try {
          setDetail(await api<ConversationDetail>(`/conversations/${id}`));
          await list.reload();
        } catch {}
    } finally {
      setBusy(false);
    }
  };
  /**
   * 用途：将人工确认决定提交给 Java。
   * 输入：approved：批准或拒绝。
   * 输出：Promise<void>；刷新草稿与历史，记录已创建工单。
   */
  const confirm = async (approved: boolean) => {
    // 只提交草稿 ID 和决定，工单内容从后端已保存的草稿读取，避免前端篡改后直接落库。
    if (!selected || !detail?.pendingProposal) return;
    setBusy(true);
    setError("");
    try {
      const result = await post<{ answer: string; ticket: TicketType | null }>(
        `/conversations/${selected}/confirm`,
        { proposalId: detail.pendingProposal.id, approved },
      );
      setCreatedTicket(result.ticket);
      setDetail(await api<ConversationDetail>(`/conversations/${selected}`));
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  /**
   * 用途：删除用户选中的会话。
   * 输入：无参数，读取 deleting 状态。
   * 输出：Promise<void>；删除后刷新列表并清理当前选择。
   */
  const remove = async () => {
    if (!deleting) return;
    setBusy(true);
    try {
      await api(`/conversations/${deleting}`, { method: "DELETE" });
      if (selected === deleting) fresh();
      setDeleting(null);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const modelConfigured = system.data?.ai.modelConfigured === true;
  // “已配置”仅用于状态提示；本次是否真的调用模型，要看回答中的 mode 字段。
  const aiUnavailable = system.data?.ai.status === "unavailable";
  const modeKnown = typeof system.data?.ai.modelConfigured === "boolean";
  return (
    <div className="assistant-layout">
      <aside className="conversation-sidebar">
        <div className="conversation-heading">
          <h2>知识助手</h2>
          <span>你的专属知识工作空间</span>
        </div>
        <button
          className="button primary new-chat"
          onClick={fresh}
          disabled={busy}
        >
          <Plus size={17} />
          新建对话
        </button>
        <div className="conversation-section-label">
          历史会话 <span>{list.data?.length || 0}</span>
        </div>
        <ErrorBox message={list.error} retry={list.reload} />
        <div className="conversation-list">
          {list.loading && !list.data ? (
            <Loading />
          ) : list.data?.length ? (
            list.data.map((c) => (
              <div
                className={`conversation-item ${selected === c.id ? "selected" : ""}`}
                key={c.id}
              >
                <button disabled={busy} onClick={() => void select(c.id)}>
                  <MessagesSquare size={16} />
                  <span>
                    <strong>{c.title || "新对话"}</strong>
                    <small>{formatDate(c.updatedAt || c.createdAt)}</small>
                  </span>
                </button>
                <button
                  className="icon-button"
                  disabled={busy}
                  title="删除会话"
                  aria-label={`删除会话 ${c.title}`}
                  onClick={() => setDeleting(c.id)}
                >
                  <Trash2 size={14} />
                </button>
              </div>
            ))
          ) : (
            <p className="small-empty">新的想法，从第一段对话开始。</p>
          )}
        </div>
        <div className="conversation-security">
          <ShieldCheck size={17} />
          <p>
            仅检索当前授权文档
            <br />
            会话仅对本人可见
          </p>
        </div>
      </aside>
      <section className="chat-workspace">
        <div className="chat-header">
          <div className="assistant-avatar">
            <Bot size={24} />
          </div>
          <div>
            <h2>{detail?.title || "企业知识助手"}</h2>
            <p>
              {system.loading
                ? "正在确认运行模式…"
                : aiUnavailable
                  ? "AI 服务不可用 · 请检查系统状态"
                  : system.error || !modeKnown
                    ? "模型状态无法确认"
                    : modelConfigured
                      ? "模型已配置 · 回答以实际调用结果为准"
                      : "仅检索模式 · 未调用模型"}
            </p>
          </div>
          <span className={`mode-badge ${modelConfigured ? "" : "retrieval"}`}>
            {system.loading
              ? "连接中"
              : aiUnavailable
                ? "服务离线"
                : system.error || !modeKnown
                  ? "状态未知"
                  : modelConfigured
                    ? "模型已配置"
                    : "仅检索"}
          </span>
        </div>
        <div className="chat-scroll">
          {loading ? (
            <Loading />
          ) : !detail?.messages.length ? (
            <div className="chat-welcome">
              <div className="welcome-bot">
                <Sparkles size={35} />
              </div>
              <span className="eyebrow">ASK. DISCOVER. RESOLVE.</span>
              <h1>你好，有什么可以帮你？</h1>
              <p>
                提问、查找制度或解决工作中的问题。
                <br />
                我会从你有权访问的知识中寻找依据。
              </p>
              <div className="prompt-cards">
                {[
                  "如何申请办公设备？",
                  "请查找员工请假流程",
                  "遇到网络问题应该如何处理？",
                ].map((q, i) => (
                  <button key={q} onClick={() => setQuestion(q)}>
                    <span>0{i + 1}</span>
                    <strong>{q}</strong>
                    <ArrowIcon />
                  </button>
                ))}
              </div>
              <div className="knowledge-note">
                <ShieldCheck size={14} />
                答案来源于企业文档，重要操作需要你确认
              </div>
            </div>
          ) : (
            <div className="messages">
              {detail.messages.map((m) => (
                <article className={`message ${m.role}`} key={m.id}>
                  <div className="message-avatar">
                    {m.role === "assistant" ? <Bot size={20} /> : "我"}
                  </div>
                  <div className="message-body">
                    <div className="message-meta">
                      <strong>
                        {m.role === "assistant" ? "协知助手" : "你"}
                      </strong>
                      <time>{formatDate(m.createdAt)}</time>
                    </div>
                    <div className="message-content">{m.content}</div>
                    {m.role === "assistant" && (
                      <CitedSources
                        content={m.content}
                        sources={m.sources}
                        onSelect={setSource}
                      />
                    )}
                  </div>
                </article>
              ))}
              {lastAnswer && (
                <div className="answer-meta">
                  <Badge
                    value={
                      lastAnswer.mode === "retrieval-only"
                        ? "仅检索，未调用模型"
                        : "模型生成回答"
                    }
                  />
                  {lastAnswer.trace?.length > 0 && (
                    <details>
                      <summary>查看处理过程</summary>
                      <p>{lastAnswer.trace.join(" → ")}</p>
                    </details>
                  )}
                </div>
              )}
              {detail.pendingProposal && (
                <div className="proposal-card">
                  <div className="section-heading">
                    <h3>
                      <Ticket size={18} />
                      待确认的工单草稿
                    </h3>
                    <Badge value={detail.pendingProposal.priority} />
                  </div>
                  <h4>{detail.pendingProposal.title}</h4>
                  <p className="pre-wrap">
                    {detail.pendingProposal.description}
                  </p>
                  <p className="muted">
                    确认后将正式创建工单；在此之前，不会写入工单系统。
                  </p>
                  <div className="actions">
                    <button
                      className="button primary"
                      disabled={busy}
                      onClick={() => void confirm(true)}
                    >
                      <Check size={17} />
                      确认创建工单
                    </button>
                    <button
                      className="button secondary"
                      disabled={busy}
                      onClick={() => void confirm(false)}
                    >
                      <X size={17} />
                      暂不创建
                    </button>
                  </div>
                </div>
              )}
              {createdTicket && (
                <div className="success-box">
                  工单 {createdTicket.number} 已创建。
                  <button
                    className="text-button"
                    onClick={() => openTicket(createdTicket.id)}
                  >
                    查看工单
                  </button>
                </div>
              )}
            </div>
          )}
          {busy && (
            <div className="chat-thinking">
              <span className="typing-dot" />
              <span className="typing-dot" />
              <span className="typing-dot" />
              正在处理，请稍候…
            </div>
          )}
          <div ref={bottom} />
        </div>
        <div className="composer-area">
          <ErrorBox message={error} />
          <form className="composer" onSubmit={send}>
            <textarea
              aria-label="输入问题"
              placeholder={
                detail?.pendingProposal
                  ? "请先确认或取消上方工单草稿"
                  : "向企业知识提问…"
              }
              value={question}
              maxLength={4000}
              disabled={busy || loading || !!detail?.pendingProposal}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (
                  e.key === "Enter" &&
                  !e.shiftKey &&
                  !e.nativeEvent.isComposing
                ) {
                  e.preventDefault();
                  if (question.trim()) void send(e);
                }
              }}
            />
            <div className="composer-footer">
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={requestTicket}
                  disabled={busy || !!detail?.pendingProposal}
                  onChange={(e) => setRequestTicket(e.target.checked)}
                />
                <Ticket size={15} />
                同时生成工单草稿
              </label>
              <span>{question.length}/4000</span>
              <button
                aria-label="发送问题"
                className="send-button"
                disabled={
                  busy ||
                  loading ||
                  !question.trim() ||
                  !!detail?.pendingProposal
                }
              >
                <Send size={19} />
              </button>
            </div>
          </form>
          <p className="composer-hint">
            Enter 发送 · Shift + Enter 换行 · 请核对来源，勿输入密码或敏感凭据
          </p>
        </div>
      </section>
      {source && (
        <Modal title="知识来源" wide onClose={() => setSource(null)}>
          <div className="source-detail-heading">
            <FileText size={25} />
            <div>
              <h3>{source.title}</h3>
              <p>
                {source.filename} · v{source.version} · 分段{" "}
                {source.chunkIndex + 1}
              </p>
            </div>
          </div>
          <blockquote className="source-excerpt">{source.content}</blockquote>
          <p className="muted">
            这是检索到的文档片段。下载原件时将重新校验当前访问权限。
          </p>
          <ErrorBox message={error} />
          <div className="modal-footer">
            <button
              className="button primary"
              onClick={async () => {
                try {
                  await downloadDocument(source.documentId, source.filename);
                } catch (e) {
                  setError(errorMessage(e));
                }
              }}
            >
              <Download size={16} />
              下载原件
            </button>
          </div>
        </Modal>
      )}
      {deleting && (
        <Modal title="删除会话" onClose={() => !busy && setDeleting(null)}>
          <p>删除后将清除该会话历史及待确认草稿。已经创建的工单不受影响。</p>
          <ErrorBox message={error} />
          <div className="modal-footer">
            <button
              className="button secondary"
              onClick={() => setDeleting(null)}
              disabled={busy}
            >
              取消
            </button>
            <button
              className="button danger-fill"
              onClick={() => void remove()}
              disabled={busy}
            >
              确认删除
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
/**
 * 用途：只展示正文实际引用的候选片段，保留原编号。
 * 输入：content：正文；sources：候选列表；onSelect：预览回调。
 * 输出：来源卡片 JSX；没有有效引用时 null。
 */
function CitedSources({
  content,
  sources,
  onSelect,
}: {
  content: string;
  sources: Source[];
  onSelect: (source: Source) => void;
}) {
  // sources 是交给模型的全部候选，不代表正文实际引用了每一项。
  // 只展示正文 [n] 对应的片段，并保留原编号：只引用 [2] 时不能把卡片改成 [1]。
  // 不修改原始候选列表，后端仍需用它复核权限与版本；历史消息也通过此处展示。
  const numbers = new Set(
    Array.from(content.matchAll(/\[(\d+)\]/g), (match) => Number(match[1])),
  );
  const cited = (sources ?? [])
    .map((source, index) => ({ source, number: index + 1 }))
    .filter(({ number }) => numbers.has(number));
  if (!cited.length) return null;
  return (
    <div className="source-cards">
      <span className="source-heading">参考来源 · {cited.length}</span>
      {cited.map(({ source, number }) => (
        <button
          key={`${source.documentId}-${source.chunkIndex}-${number}`}
          className="source-card"
          onClick={() => onSelect(source)}
        >
          <span className="source-number">{number}</span>
          <FileText size={16} />
          <strong>{source.title}</strong>
          <span>
            v{source.version} · 分段 {source.chunkIndex + 1}
          </span>
        </button>
      ))}
    </div>
  );
}

/**
 * 用途：渲染建议问题卡片的箭头。
 * 输入：无参数。
 * 输出：箭头 JSX。
 */
function ArrowIcon() {
  return <span className="prompt-arrow">↗</span>;
}
