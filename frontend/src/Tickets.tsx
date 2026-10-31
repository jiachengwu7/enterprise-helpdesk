import { useEffect, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  CheckCircle2,
  Clock,
  MessageSquare,
  Plus,
  Search,
  Send,
  Ticket as TicketIcon,
  UserRound,
} from "lucide-react";
import { api, errorMessage, post } from "./api";
import type { Ticket, TicketDetail, User } from "./types";
import { ticketActions } from "./permissions";
import {
  Badge,
  Empty,
  ErrorBox,
  Field,
  Loading,
  Modal,
  PageTitle,
  Refresh,
  formatDate,
  useLoad,
} from "./ui";
/**
 * 用途：展示工单列表及详情切换入口。
 * 输入：user：用户；initialId：可空初始工单；clearInitial：清理初始选择回调。
 * 输出：工单页面 JSX。
 */
export default function Tickets({
  user,
  initialId,
  clearInitial,
}: {
  user: User;
  initialId: string | null;
  clearInitial: () => void;
}) {
  const list = useLoad<Ticket[]>(() => api("/tickets"));
  const [selected, setSelected] = useState<string | null>(initialId),
    [creating, setCreating] = useState(false),
    [search, setSearch] = useState(""),
    [status, setStatus] = useState("ALL"),
    [priority, setPriority] = useState("ALL");
  useEffect(() => {
    if (initialId) {
      setSelected(initialId);
      clearInitial();
    }
  }, [initialId]);
  const tickets =
    list.data?.filter(
      (t) =>
        (status === "ALL" || t.status === status) &&
        (priority === "ALL" || t.priority === priority) &&
        `${t.title} ${t.number} ${t.creatorName}`
          .toLowerCase()
          .includes(search.toLowerCase()),
    ) || [];
  if (selected)
    return (
      <TicketView
        id={selected}
        user={user}
        onBack={() => {
          setSelected(null);
          void list.reload();
        }}
      />
    );
  return (
    <>
      <PageTitle
        eyebrow="SERVICE & COLLABORATION"
        title="工单协作"
        description="让问题有归属，让进展看得见。"
      >
        <Refresh onClick={list.reload} busy={list.loading} />
        <button className="button primary" onClick={() => setCreating(true)}>
          <Plus size={17} />
          创建工单
        </button>
      </PageTitle>
      <div className="ticket-summary">
        {[
          { id: "ALL", text: "全部工单" },
          { id: "OPEN", text: "待处理" },
          { id: "IN_PROGRESS", text: "处理中" },
          { id: "RESOLVED", text: "已解决" },
          { id: "CLOSED", text: "已关闭" },
        ].map((s) => (
          <button
            key={s.id}
            className={status === s.id ? "selected" : ""}
            onClick={() => setStatus(s.id)}
          >
            {s.text}
            <strong>
              {list.data
                ? list.data.filter((t) => s.id === "ALL" || t.status === s.id)
                    .length
                : "—"}
            </strong>
          </button>
        ))}
      </div>
      <div className="panel">
        <div className="toolbar">
          <div className="search-field">
            <Search size={17} />
            <input
              aria-label="搜索工单"
              placeholder="搜索工单标题、编号或创建人"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <select
            aria-label="工单优先级筛选"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
          >
            <option value="ALL">全部优先级</option>
            <option value="HIGH">高优先级</option>
            <option value="MEDIUM">中优先级</option>
            <option value="LOW">低优先级</option>
          </select>
          <span className="muted">{tickets.length} 条结果</span>
        </div>
        <ErrorBox message={list.error} retry={list.reload} />
        {list.loading && !list.data ? (
          <Loading />
        ) : tickets.length ? (
          <div className="table-wrap">
            <table className="ticket-table">
              <thead>
                <tr>
                  <th>工单 / 标题</th>
                  <th>状态</th>
                  <th>优先级</th>
                  <th>创建人 / 部门</th>
                  <th>处理人</th>
                  <th>更新时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {tickets.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <button
                        className="table-title"
                        onClick={() => setSelected(t.id)}
                      >
                        <small>{t.number}</small>
                        <strong>{t.title}</strong>
                      </button>
                    </td>
                    <td>
                      <Badge value={t.status} />
                    </td>
                    <td>
                      <Badge value={t.priority} />
                    </td>
                    <td>
                      {t.creatorName}
                      <small className="block muted">{t.departmentId}</small>
                    </td>
                    <td>
                      {t.assigneeName || <span className="muted">待分配</span>}
                    </td>
                    <td className="muted">{formatDate(t.updatedAt)}</td>
                    <td>
                      <button
                        className="text-button"
                        onClick={() => setSelected(t.id)}
                      >
                        查看
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="暂无匹配的工单"
            description="创建工单说明问题，或通过知识助手整理工单草稿。"
          />
        )}
      </div>
      {creating && (
        <CreateTicket
          onClose={() => setCreating(false)}
          onCreated={(t) => {
            setCreating(false);
            setSelected(t.id);
            void list.reload();
          }}
        />
      )}
    </>
  );
}
/**
 * 用途：渲染手动创建工单的弹窗。
 * 输入：onClose：关闭回调；onCreated：接收新工单的回调。
 * 输出：创建表单 JSX；为本次创建意图保留幂等键。
 */
function CreateTicket({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (t: Ticket) => void;
}) {
  // 弹窗的一次挂载对应一次创建意图，useState 保留幂等键，重复提交时不会重新生成。
  // 关闭再新建会得到新键；同一键修改内容后重试，后端会拒绝以避免歧义。
  const [title, setTitle] = useState(""),
    [description, setDescription] = useState(""),
    [priority, setPriority] = useState("MEDIUM"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [idempotencyKey] = useState(() => crypto.randomUUID());
  /**
   * 用途：提交工单并复用当前幂等键。
   * 输入：e：表单事件；标题、描述、优先级来自状态。
   * 输出：Promise<void>；成功调用 onCreated，失败显示提示。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onCreated(
        await post<Ticket>("/tickets", {
          title: title.trim(),
          description: description.trim(),
          priority,
          idempotencyKey,
        }),
      );
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="创建协作工单" onClose={() => !busy && onClose()}>
      <form onSubmit={submit}>
        <Field label="工单标题">
          <input
            value={title}
            required
            maxLength={200}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="简要描述需要解决的问题"
          />
        </Field>
        <Field label="问题描述">
          <textarea
            value={description}
            required
            maxLength={10000}
            rows={6}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="请描述现象、影响范围及已经尝试的操作"
          />
        </Field>
        <Field label="优先级">
          <select
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
          >
            <option value="LOW">低 · 一般咨询</option>
            <option value="MEDIUM">中 · 影响日常工作</option>
            <option value="HIGH">高 · 需要尽快处理</option>
          </select>
        </Field>
        <p className="muted">工单将归属你的部门。请求重试不会重复创建工单。</p>
        <ErrorBox message={error} />
        <div className="modal-footer">
          <button
            type="button"
            className="button secondary"
            onClick={onClose}
            disabled={busy}
          >
            取消
          </button>
          <button
            className="button primary"
            disabled={busy || !title.trim() || !description.trim()}
          >
            {busy ? "创建中…" : "提交工单"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
/**
 * 用途：展示单张工单、评论和可用操作。
 * 输入：id：工单 ID；user：用户；onBack：返回回调。
 * 输出：工单详情 JSX；请求详情和可选处理人。
 */
function TicketView({
  id,
  user,
  onBack,
}: {
  id: string;
  user: User;
  onBack: () => void;
}) {
  const detail = useLoad<TicketDetail>(() => api(`/tickets/${id}`), [id]);
  const staff = useLoad<User[]>(
    () => (user.role === "EMPLOYEE" ? Promise.resolve([]) : api("/users")),
    [user.role],
  );
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [comment, setComment] = useState(""),
    [assignee, setAssignee] = useState(""),
    [resolution, setResolution] = useState(""),
    [rating, setRating] = useState("5"),
    [action, setAction] = useState<"resolve" | "close" | null>(null);
  useEffect(() => {
    setAssignee(detail.data?.assigneeId || "");
  }, [detail.data?.assigneeId]);
  /**
   * 用途：提交工单状态或处理人变更。
   * 输入：body：PATCH 字段对象。
   * 输出：Promise<void>；成功刷新详情并关闭操作弹窗。
   */
  const update = async (body: Record<string, unknown>) => {
    setBusy(true);
    setError("");
    try {
      await api(`/tickets/${id}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      });
      setAction(null);
      await detail.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  /**
   * 用途：提交非空评论并刷新详情。
   * 输入：e：表单事件；comment：组件内的评论状态。
   * 输出：Promise<void>；成功后清空输入并刷新。
   */
  const sendComment = async (e: FormEvent) => {
    e.preventDefault();
    if (!comment.trim()) return;
    setBusy(true);
    setError("");
    try {
      await post(`/tickets/${id}/comments`, { content: comment.trim() });
      setComment("");
      await detail.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const t = detail.data;
  const actions = t ? ticketActions(user, t) : null;
  return (
    <>
      <button className="back-button" onClick={onBack}>
        <ArrowLeft size={17} />
        返回工单列表
      </button>
      <ErrorBox message={detail.error} retry={detail.reload} />
      {detail.loading && !t ? (
        <Loading />
      ) : (
        t && (
          <>
            <PageTitle
              eyebrow={t.number}
              title={t.title}
              description={`创建于 ${formatDate(t.createdAt)} · ${t.creatorName}`}
            >
              <Badge value={t.status} />
              <Refresh onClick={detail.reload} busy={detail.loading} />
            </PageTitle>
            <div className="lifecycle">
              {["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"].map((s, i) => (
                <div
                  className={
                    ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"].indexOf(
                      t.status,
                    ) >= i
                      ? "done"
                      : ""
                  }
                  key={s}
                >
                  <span>
                    {["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"].indexOf(
                      t.status,
                    ) > i ? (
                      <CheckCircle2 size={17} />
                    ) : (
                      i + 1
                    )}
                  </span>
                  <Badge value={s} />
                </div>
              ))}
            </div>
            <ErrorBox message={error} />
            <div className="ticket-detail-grid">
              <div>
                <section className="panel detail-panel">
                  <h2>
                    <TicketIcon size={19} />
                    问题描述
                  </h2>
                  <p className="pre-wrap">{t.description}</p>
                  {t.resolution && (
                    <div className="resolution">
                      <h3>
                        <CheckCircle2 size={18} />
                        解决方案
                      </h3>
                      <p className="pre-wrap">{t.resolution}</p>
                    </div>
                  )}
                  {t.rating && (
                    <div className="rating-display">
                      服务评价{" "}
                      <span>
                        {"★".repeat(t.rating)}
                        {"☆".repeat(5 - t.rating)}
                      </span>{" "}
                      {t.rating}/5
                    </div>
                  )}
                </section>
                <section className="panel detail-panel comments-panel">
                  <h2>
                    <MessageSquare size={19} />
                    协作记录{" "}
                    <span className="muted">{t.comments?.length || 0}</span>
                  </h2>
                  {t.comments?.length ? (
                    t.comments.map((c) => (
                      <div className="comment" key={c.id}>
                        <div className="avatar">{c.authorName.slice(0, 1)}</div>
                        <div>
                          <strong>{c.authorName}</strong>
                          <time>{formatDate(c.createdAt)}</time>
                          <p className="pre-wrap">{c.content}</p>
                        </div>
                      </div>
                    ))
                  ) : (
                    <p className="muted">
                      暂无评论，可补充信息或记录处理进展。
                    </p>
                  )}
                  <form onSubmit={sendComment}>
                    <textarea
                      aria-label="工单评论"
                      placeholder="补充信息，或记录处理进展…"
                      value={comment}
                      maxLength={5000}
                      rows={3}
                      onChange={(e) => setComment(e.target.value)}
                    />
                    <div className="comment-send">
                      <button
                        className="button primary"
                        disabled={busy || !comment.trim()}
                      >
                        <Send size={15} />
                        发送评论
                      </button>
                    </div>
                  </form>
                </section>
              </div>
              <aside>
                <section className="panel detail-panel ticket-info">
                  <h2>工单信息</h2>
                  <dl>
                    <div>
                      <dt>优先级</dt>
                      <dd>
                        <Badge value={t.priority} />
                      </dd>
                    </div>
                    <div>
                      <dt>所属部门</dt>
                      <dd>{t.departmentId}</dd>
                    </div>
                    <div>
                      <dt>创建人</dt>
                      <dd>{t.creatorName}</dd>
                    </div>
                    <div>
                      <dt>处理人</dt>
                      <dd>{t.assigneeName || "尚未分配"}</dd>
                    </div>
                    <div>
                      <dt>最后更新</dt>
                      <dd>{formatDate(t.updatedAt)}</dd>
                    </div>
                  </dl>
                  {actions?.assign && (
                    <>
                      <hr />
                      <Field label="分配处理人">
                        <select
                          value={assignee}
                          onChange={(e) => setAssignee(e.target.value)}
                        >
                          <option value="">选择本部门处理员</option>
                          {staff.data
                            ?.filter(
                              (u) =>
                                u.departmentId === t.departmentId &&
                                (u.role === "AGENT" || u.role === "ADMIN"),
                            )
                            .map((u) => (
                              <option key={u.id} value={u.id}>
                                {u.displayName}（{u.username}）
                              </option>
                            ))}
                        </select>
                      </Field>
                      <ErrorBox message={staff.error} retry={staff.reload} />
                      <button
                        className="button secondary full-width"
                        disabled={
                          busy || !assignee || assignee === t.assigneeId
                        }
                        onClick={() => void update({ assigneeId: assignee })}
                      >
                        <UserRound size={16} />
                        保存分配
                      </button>
                    </>
                  )}
                  {(actions?.start || actions?.resolve || actions?.close) && (
                    <div className="ticket-next-action">
                      {actions.start && (
                        <button
                          className="button primary full-width"
                          disabled={busy}
                          onClick={() => void update({ status: "IN_PROGRESS" })}
                        >
                          <Clock size={16} />
                          开始处理
                        </button>
                      )}
                      {actions.resolve && (
                        <button
                          className="button primary full-width"
                          disabled={busy}
                          onClick={() => setAction("resolve")}
                        >
                          <CheckCircle2 size={16} />
                          标记已解决
                        </button>
                      )}
                      {actions.close && (
                        <button
                          className="button primary full-width"
                          disabled={busy}
                          onClick={() => setAction("close")}
                        >
                          评价并关闭工单
                        </button>
                      )}
                    </div>
                  )}
                  <p className="muted help-text">
                    处理员推进处理状态；解决后，由创建人或管理员评价并关闭。
                  </p>
                </section>
              </aside>
            </div>
            {action && (
              <Modal
                title={action === "resolve" ? "填写解决方案" : "评价并关闭工单"}
                onClose={() => !busy && setAction(null)}
              >
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    void update(
                      action === "resolve"
                        ? { status: "RESOLVED", resolution: resolution.trim() }
                        : { status: "CLOSED", rating: Number(rating) },
                    );
                  }}
                >
                  {action === "resolve" ? (
                    <Field label="解决方案">
                      <textarea
                        rows={5}
                        required
                        value={resolution}
                        maxLength={10000}
                        onChange={(e) => setResolution(e.target.value)}
                        placeholder="说明原因与处理结果"
                      />
                    </Field>
                  ) : (
                    <>
                      <p>确认问题已经解决，并为本次服务评分。</p>
                      <Field label="服务评分">
                        <select
                          value={rating}
                          onChange={(e) => setRating(e.target.value)}
                        >
                          {[5, 4, 3, 2, 1].map((n) => (
                            <option key={n} value={n}>
                              {n} 星 {"★".repeat(n)}
                            </option>
                          ))}
                        </select>
                      </Field>
                    </>
                  )}
                  <ErrorBox message={error} />
                  <div className="modal-footer">
                    <button
                      type="button"
                      className="button secondary"
                      disabled={busy}
                      onClick={() => setAction(null)}
                    >
                      取消
                    </button>
                    <button
                      className="button primary"
                      disabled={
                        busy || (action === "resolve" && !resolution.trim())
                      }
                    >
                      {busy ? "保存中…" : "确认提交"}
                    </button>
                  </div>
                </form>
              </Modal>
            )}
          </>
        )
      )}
    </>
  );
}
