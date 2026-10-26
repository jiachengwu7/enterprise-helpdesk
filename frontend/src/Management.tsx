import { useState, type FormEvent } from "react";
import {
  Activity,
  CheckCircle2,
  Database,
  KeyRound,
  Plus,
  Server,
  ShieldCheck,
  XCircle,
} from "lucide-react";
import { api, errorMessage, post } from "./api";
import type { Department, Role, SystemStatus, User } from "./types";
import {
  Badge,
  Empty,
  ErrorBox,
  Field,
  Loading,
  Modal,
  PageTitle,
  Refresh,
  useLoad,
} from "./ui";
export function System() {
  const state = useLoad<SystemStatus>(() => api("/system/status"));
  const ai = state.data?.ai;
  const healthy = (value: unknown) =>
    value === true ||
    ["ok", "healthy", "ready", "connected"].includes(
      String(value).toLowerCase(),
    );
  const rows = [
    { name: "业务数据库", icon: Database, value: state.data?.database },
    { name: "AI 服务", icon: Server, value: ai?.status },
    { name: "向量数据库", icon: Database, value: ai?.database },
    { name: "异步任务队列", icon: Activity, value: ai?.redis },
    { name: "文档对象存储", icon: Server, value: ai?.storage },
  ];
  return (
    <>
      <PageTitle
        eyebrow="SYSTEM OBSERVABILITY"
        title="系统状态"
        description="查看当前服务的真实运行状态与模型配置情况。"
      >
        <Refresh onClick={state.reload} busy={state.loading} />
      </PageTitle>
      <ErrorBox message={state.error} retry={state.reload} />
      {state.loading && !state.data ? (
        <Loading />
      ) : (
        <>
          <div className="system-banner">
            <ShieldCheck size={27} />
            <div>
              <h2>{state.data ? "运行状态概览" : "暂时无法获取服务状态"}</h2>
              <p>
                仅显示非敏感健康信息 · 平台版本 {state.data?.version || "未知"}
              </p>
            </div>
            <span className="badge">手动刷新获取最新状态</span>
          </div>
          <div className="health-grid">
            {rows.map((r) => (
              <section className="panel health-card" key={r.name}>
                <div className="health-icon">
                  <r.icon size={23} />
                </div>
                <h3>{r.name}</h3>
                <div
                  className={`health-value ${healthy(r.value) ? "healthy" : "unhealthy"}`}
                >
                  {healthy(r.value) ? (
                    <CheckCircle2 size={17} />
                  ) : (
                    <XCircle size={17} />
                  )}
                  <strong>
                    {r.value === undefined
                      ? "未知 / 未连接"
                      : healthy(r.value)
                        ? "运行正常"
                        : String(r.value)}
                  </strong>
                </div>
              </section>
            ))}
          </div>
          <div className="model-grid">
            <section className="panel detail-panel">
              <h2>
                <KeyRound size={20} />
                模型与检索
              </h2>
              <dl className="model-status">
                <div>
                  <dt>对话模型</dt>
                  <dd>
                    <Badge
                      value={
                        ai?.modelConfigured === true
                          ? "已配置"
                          : ai?.modelConfigured === false
                            ? "未配置"
                            : "状态未知"
                      }
                    />
                  </dd>
                </div>
                <div>
                  <dt>嵌入模型</dt>
                  <dd>
                    <Badge
                      value={
                        ai?.embeddingConfigured === true
                          ? "已配置"
                          : ai?.embeddingConfigured === false
                            ? "未配置"
                            : "状态未知"
                      }
                    />
                  </dd>
                </div>
                <div>
                  <dt>当前索引模式</dt>
                  <dd>
                    {typeof ai?.indexMode === "string" ? ai.indexMode : "未知"}
                  </dd>
                </div>
                <div>
                  <dt>AI 服务版本</dt>
                  <dd>
                    {typeof ai?.version === "string" ? ai.version : "未知"}
                  </dd>
                </div>
              </dl>
            </section>
            <section className="panel detail-panel config-note">
              <h2>本机模型配置</h2>
              <p>
                模型由部署者在 <code>ai-service/.env</code>{" "}
                中配置，并在重新启动服务后生效。
              </p>
              <p>
                未配置对话模型时，助手仅返回授权文档的检索证据，并明确标注“仅检索，未调用模型”。模型调用失败会显示错误。
              </p>
              <p className="muted">
                界面不收集 API
                Key、不展示密钥，也不接受任意模型服务地址。配置存在不代表真实调用已通过验证。
              </p>
            </section>
          </div>
        </>
      )}
    </>
  );
}
export function UserManagement({ departments }: { departments: Department[] }) {
  const list = useLoad<User[]>(() => api("/users"));
  const [creating, setCreating] = useState(false);
  return (
    <>
      <PageTitle
        eyebrow="PEOPLE & ACCESS"
        title="用户管理"
        description="由管理员创建账户，通过角色与部门明确访问边界。"
      >
        <Refresh onClick={list.reload} busy={list.loading} />
        <button className="button primary" onClick={() => setCreating(true)}>
          <Plus size={17} />
          创建用户
        </button>
      </PageTitle>
      <div className="notice">
        <ShieldCheck size={17} />
        <span>
          员工查看授权知识和自己的工单；处理员管理本部门；管理员管理所有业务资源。
        </span>
      </div>
      <section className="panel">
        <div className="section-heading">
          <h2>企业成员</h2>
          <span className="muted">{list.data?.length ?? "—"} 位成员</span>
        </div>
        <ErrorBox message={list.error} retry={list.reload} />
        {list.loading && !list.data ? (
          <Loading />
        ) : list.data?.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>成员</th>
                  <th>用户名</th>
                  <th>部门</th>
                  <th>角色</th>
                </tr>
              </thead>
              <tbody>
                {list.data.map((u) => (
                  <tr key={u.id}>
                    <td>
                      <div className="member">
                        <div className="avatar">
                          {u.displayName.slice(0, 1)}
                        </div>
                        <strong>{u.displayName}</strong>
                      </div>
                    </td>
                    <td>{u.username}</td>
                    <td>
                      {departments.find((d) => d.id === u.departmentId)?.name ||
                        u.departmentId}
                    </td>
                    <td>
                      <Badge value={u.role} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty title="暂无用户" />
        )}
      </section>
      {creating && (
        <UserForm
          departments={departments}
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            void list.reload();
          }}
        />
      )}
    </>
  );
}
function UserForm({
  departments,
  onClose,
  onCreated,
}: {
  departments: Department[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const [username, setUsername] = useState(""),
    [displayName, setDisplayName] = useState(""),
    [password, setPassword] = useState(""),
    [departmentId, setDepartmentId] = useState(departments[0]?.id || "IT"),
    [role, setRole] = useState<Role>("EMPLOYEE"),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await post("/users", {
        username: username.trim(),
        displayName: displayName.trim(),
        password,
        departmentId,
        role,
      });
      onCreated();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="创建企业用户" onClose={() => !busy && onClose()}>
      <form onSubmit={submit}>
        <div className="form-grid">
          <Field label="用户名">
            <input
              required
              minLength={3}
              maxLength={60}
              autoComplete="off"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </Field>
          <Field label="显示姓名">
            <input
              required
              maxLength={80}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </Field>
        </div>
        <Field label="初始密码">
          <input
            type="password"
            required
            minLength={8}
            maxLength={128}
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <div className="form-grid">
          <Field label="部门">
            <select
              value={departmentId}
              onChange={(e) => setDepartmentId(e.target.value)}
            >
              {departments.map((d) => (
                <option value={d.id} key={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="角色">
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as Role)}
            >
              <option value="EMPLOYEE">员工</option>
              <option value="AGENT">处理员</option>
              <option value="ADMIN">管理员</option>
            </select>
          </Field>
        </div>
        <p className="muted">
          密码至少 8 位。请通过可信渠道将账户信息交给本人。
        </p>
        <ErrorBox message={error} />
        <div className="modal-footer">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={onClose}
          >
            取消
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "创建中…" : "确认创建用户"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
