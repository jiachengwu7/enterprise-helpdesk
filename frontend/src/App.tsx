import { useEffect, useState, type FormEvent } from "react";
import {
  BookOpen,
  LayoutDashboard,
  MessagesSquare,
  Ticket,
  Activity,
  Users,
  LogOut,
  ArrowUpRight,
  ShieldCheck,
  Network,
  ChevronRight,
  Menu,
  X,
  Command,
} from "lucide-react";
import { api, errorMessage, getToken, post, setToken } from "./api";
import type {
  Dashboard,
  Department,
  Ticket as TicketType,
  User,
} from "./types";
import {
  Badge,
  Empty,
  ErrorBox,
  Field,
  Loading,
  PageTitle,
  Refresh,
  formatDate,
  labels,
  useLoad,
} from "./ui";
import Documents from "./Documents";
import Assistant from "./Assistant";
import Tickets from "./Tickets";
import { System, UserManagement } from "./Management";
export type Page =
  "dashboard" | "assistant" | "documents" | "tickets" | "system" | "users";
const navigation = [
  { id: "dashboard", title: "工作概览", icon: LayoutDashboard },
  { id: "assistant", title: "知识助手", icon: MessagesSquare },
  { id: "documents", title: "企业知识库", icon: BookOpen },
  { id: "tickets", title: "工单协作", icon: Ticket },
  { id: "system", title: "系统状态", icon: Activity },
  { id: "users", title: "用户管理", icon: Users },
] as const;
/**
 * 用途：管理登录态、导航和各业务页面入口。
 * 输入：无显式参数，使用浏览器令牌及 API。
 * 输出：应用根组件的 JSX；加载身份并监听过期事件。
 */
export default function App() {
  const [user, setUser] = useState<User | null>(null),
    [restoring, setRestoring] = useState(!!getToken()),
    [authError, setAuthError] = useState(""),
    [page, setPage] = useState<Page>("dashboard"),
    [mobile, setMobile] = useState(false),
    [ticketId, setTicketId] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    if (getToken())
      api<User>("/auth/me")
        .then((u) => {
          if (live) setUser(u);
        })
        .catch((e) => {
          if (live) setAuthError(errorMessage(e));
        })
        .finally(() => {
          if (live) setRestoring(false);
        });
    /**
     * 用途：响应全局登录过期事件。
     * 输入：无参数。
     * 输出：void；清理当前用户并显示登录提示。
     */
    const expired = () => {
      setUser(null);
      setAuthError("会话已过期，请重新登录。");
    };
    window.addEventListener("auth-expired", expired);
    return () => {
      live = false;
      window.removeEventListener("auth-expired", expired);
    };
  }, []);
  const departments = useLoad<Department[]>(
    () => (user ? api("/departments") : Promise.resolve([])),
    [user?.id],
  );
  /**
   * 用途：切换业务页面。
   * 输入：target：页面标识。
   * 输出：void；更新当前页面并收起移动端菜单。
   */
  const go = (target: Page) => {
    setPage(target);
    setMobile(false);
  };
  /**
   * 用途：导航到指定工单详情。
   * 输入：id：工单 ID。
   * 输出：void；更新页面和选中工单。
   */
  const openTicket = (id: string) => {
    setTicketId(id);
    go("tickets");
  };
  /**
   * 用途：撤销服务端登录并清理前端状态。
   * 输入：无参数，读取当前令牌。
   * 输出：Promise<void>；清理登录态。
   */
  const logout = async () => {
    try {
      await post("/auth/logout", {});
    } catch {}
    setToken(null);
    setUser(null);
    setAuthError("");
    setPage("dashboard");
  };
  if (restoring)
    return (
      <div className="boot">
        <Loading />
      </div>
    );
  if (!user)
    return (
      <Login
        initialError={authError}
        onLogin={(u) => {
          setUser(u);
          setAuthError("");
        }}
      />
    );
  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobile ? "is-open" : ""}`}>
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            go("dashboard");
          }}
        >
          <span className="brand-icon">
            <Network size={24} />
          </span>
          <div>
            <strong>协知</strong>
            <small>ENTERPRISE WORKSPACE</small>
          </div>
        </a>
        <button
          className="mobile-close icon-button"
          aria-label="关闭导航"
          onClick={() => setMobile(false)}
        >
          <X />
        </button>
        <div className="workspace-tag">
          <span className="live-dot" />
          企业服务工作台
        </div>
        <p className="nav-label">工作空间</p>
        <nav aria-label="主导航">
          {navigation
            .filter((n) => n.id !== "users" || user.role === "ADMIN")
            .map((n) => (
              <button
                key={n.id}
                onClick={() => go(n.id)}
                aria-current={page === n.id ? "page" : undefined}
                className={page === n.id ? "nav-item active" : "nav-item"}
              >
                <n.icon size={19} />
                <span>{n.title}</span>
                {page === n.id && <ChevronRight size={14} />}
              </button>
            ))}
        </nav>
        <div className="sidebar-note">
          <ShieldCheck size={21} />
          <strong>知识有边界，协作无障碍</strong>
          <p>
            按部门授权访问
            <br />
            每个答案都可追溯
          </p>
        </div>
        <div className="profile">
          <div className="avatar">{user.displayName.slice(0, 1)}</div>
          <div>
            <strong>{user.displayName}</strong>
            <small>
              {labels[user.role]} · {user.departmentId}
            </small>
          </div>
          <button
            className="icon-button"
            aria-label="退出登录"
            title="退出登录"
            onClick={() => void logout()}
          >
            <LogOut size={17} />
          </button>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="mobile-menu icon-button"
              aria-label="打开导航"
              onClick={() => setMobile(true)}
            >
              <Menu />
            </button>
            <span>企业工作台</span>
            <ChevronRight size={14} />
            <strong>{navigation.find((n) => n.id === page)?.title}</strong>
          </div>
          <div className="topbar-right">
            <span className="department-chip">
              <ShieldCheck size={14} />
              {departments.data?.find((d) => d.id === user.departmentId)
                ?.name || user.departmentId}
            </span>
            <span className="date">
              {new Date().toLocaleDateString("zh-CN", {
                month: "long",
                day: "numeric",
                weekday: "long",
              })}
            </span>
          </div>
        </header>
        <main className={page === "assistant" ? "main assistant-main" : "main"}>
          <ErrorBox message={departments.error} retry={departments.reload} />
          {page === "dashboard" && (
            <Overview user={user} go={go} openTicket={openTicket} />
          )}{" "}
          {page === "documents" && (
            <Documents user={user} departments={departments.data || []} />
          )}{" "}
          {page === "assistant" && <Assistant openTicket={openTicket} />}{" "}
          {page === "tickets" && (
            <Tickets
              user={user}
              initialId={ticketId}
              clearInitial={() => setTicketId(null)}
            />
          )}{" "}
          {page === "system" && <System />}{" "}
          {page === "users" && user.role === "ADMIN" && (
            <UserManagement departments={departments.data || []} />
          )}
        </main>
      </div>
    </div>
  );
}
/**
 * 用途：渲染登录表单并处理输入状态。
 * 输入：initialError：初始提示；onLogin：登录成功回调。
 * 输出：登录页面 JSX。
 */
function Login({
  initialError,
  onLogin,
}: {
  initialError: string;
  onLogin: (u: User) => void;
}) {
  const [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [error, setError] = useState(initialError),
    [busy, setBusy] = useState(false);
  /**
   * 用途：提交账号密码并传递登录后的用户。
   * 输入：e：表单事件；账号密码来自组件状态。
   * 输出：Promise<void>；保存令牌并调用 onLogin，失败显示提示。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const result = await post<{ token: string; user: User }>("/auth/login", {
        username: username.trim(),
        password,
      });
      setToken(result.token);
      onLogin(result.user);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="login-page">
      <section className="login-story">
        <div className="brand">
          <span className="brand-icon">
            <Network size={25} />
          </span>
          <div>
            <strong>协知</strong>
            <small>ENTERPRISE WORKSPACE</small>
          </div>
        </div>
        <div className="login-copy">
          <span className="eyebrow">KNOWLEDGE × COLLABORATION</span>
          <h1>
            让企业知识
            <br />
            成为行动的起点。
          </h1>
          <p>
            从一个问题，到一个可靠的答案。
            <br />
            连接知识、团队与每一件需要解决的事。
          </p>
          <div className="story-flow">
            <div>
              <BookOpen />
              <span>授权知识</span>
            </div>
            <ChevronRight />
            <div>
              <MessagesSquare />
              <span>可溯源回答</span>
            </div>
            <ChevronRight />
            <div>
              <Ticket />
              <span>协作闭环</span>
            </div>
          </div>
        </div>
        <small className="login-footer">
          企业知识助手与工单协作平台 · 本机独立部署
        </small>
      </section>
      <section className="login-panel">
        <div className="login-form">
          <div className="login-mark">
            <Command size={27} />
          </div>
          <h2>欢迎回到工作台</h2>
          <p>使用企业账户登录，继续你的工作。</p>
          <form onSubmit={submit}>
            <Field label="用户名">
              <input
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                placeholder="请输入用户名"
              />
            </Field>
            <Field label="密码">
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                placeholder="请输入密码"
              />
            </Field>
            <ErrorBox message={error} />
            <button className="button primary login-submit" disabled={busy}>
              {busy ? "正在登录…" : "登录工作台"}
              <ArrowUpRight size={18} />
            </button>
          </form>
          <div className="login-hint">
            <ShieldCheck size={17} />
            <span>
              账户由管理员创建。初始账户信息请查看本机凭据文件；页面不保存密码。
            </span>
          </div>
        </div>
      </section>
    </div>
  );
}
/**
 * 用途：展示当前用户可见的数据概览。
 * 输入：user：用户；go：页面导航；openTicket：工单跳转。
 * 输出：概览页面 JSX；请求统计数据。
 */
function Overview({
  user,
  go,
  openTicket,
}: {
  user: User;
  go: (p: Page) => void;
  openTicket: (id: string) => void;
}) {
  const stats = useLoad<Dashboard>(() => api("/dashboard"));
  const tickets = useLoad<TicketType[]>(() => api("/tickets"));
  return (
    <>
      <PageTitle
        eyebrow="YOUR WORKSPACE, CONNECTED"
        title={`${user.displayName}，欢迎回来`}
        description="知识触手可及，让每一件事都有回应。"
      >
        <Refresh
          onClick={() => {
            void stats.reload();
            void tickets.reload();
          }}
          busy={stats.loading}
        />
      </PageTitle>
      <ErrorBox message={stats.error} retry={stats.reload} />
      <section className="welcome-banner">
        <div>
          <span className="banner-label">
            <span className="live-dot" />
            从知识到解决方案
          </span>
          <h2>有问题，先问知识助手。</h2>
          <p>
            在有权访问的企业文档中寻找答案，
            <br />
            需要进一步帮助时，确认草稿即可转为协作工单。
          </p>
          <button className="button white" onClick={() => go("assistant")}>
            开始新对话
            <ArrowUpRight size={17} />
          </button>
        </div>
        <div className="banner-art" aria-hidden="true">
          <div className="orbit orbit-one" />
          <div className="orbit orbit-two" />
          <div className="art-center">
            <MessagesSquare size={44} />
          </div>
          <span className="art-chip chip-one">
            <BookOpen size={18} />
            企业知识
          </span>
          <span className="art-chip chip-two">
            <ShieldCheck size={18} />
            来源可查
          </span>
          <span className="art-chip chip-three">
            <Ticket size={18} />
            协作工单
          </span>
        </div>
      </section>
      <div className="stats-grid">
        {[
          {
            label: "可访问知识文档",
            key: "documents",
            icon: BookOpen,
            color: "blue",
          },
          {
            label: "我的可见工单",
            key: "tickets",
            icon: Ticket,
            color: "purple",
          },
          {
            label: "待处理工单",
            key: "openTickets",
            icon: Activity,
            color: "orange",
          },
          {
            label: "已解决工单",
            key: "resolvedTickets",
            icon: ShieldCheck,
            color: "teal",
          },
        ].map((item) => (
          <div className="stat-card" key={item.key}>
            <div className={`stat-icon ${item.color}`}>
              <item.icon size={21} />
            </div>
            <span>{item.label}</span>
            <strong>
              {stats.loading
                ? "…"
                : (stats.data?.[item.key as keyof Dashboard] ?? "—")}
            </strong>
            <small>当前账户授权范围</small>
          </div>
        ))}
      </div>
      <div className="overview-bottom">
        <section className="panel">
          <div className="section-heading">
            <div>
              <h2>近期工单</h2>
              <p>关注进度，及时跟进</p>
            </div>
            <button className="text-button" onClick={() => go("tickets")}>
              查看全部
              <ArrowUpRight size={14} />
            </button>
          </div>
          <ErrorBox message={tickets.error} retry={tickets.reload} />
          {tickets.loading ? (
            <Loading />
          ) : tickets.data?.length ? (
            <div className="recent-tickets">
              {[...tickets.data]
                .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
                .slice(0, 5)
                .map((t) => (
                  <button key={t.id} onClick={() => openTicket(t.id)}>
                    <div className="ticket-mini-icon">
                      <Ticket size={18} />
                    </div>
                    <div className="recent-title">
                      <strong>{t.title}</strong>
                      <small>
                        {t.number} · {formatDate(t.createdAt)}
                      </small>
                    </div>
                    <Badge value={t.status} />
                    <ChevronRight size={15} />
                  </button>
                ))}
            </div>
          ) : (
            <Empty
              title="暂无工单"
              description="可以在工单协作页面创建，或让知识助手协助整理。"
            />
          )}
        </section>
        <section className="panel quick-panel">
          <h2>工作捷径</h2>
          <p>从这里继续</p>
          {[
            {
              page: "assistant",
              icon: MessagesSquare,
              title: "检索企业知识",
              description: "带来源引用的问答",
            },
            {
              page: "documents",
              icon: BookOpen,
              title: "浏览知识库",
              description: "查看授权文档及索引状态",
            },
            {
              page: "system",
              icon: Activity,
              title: "查看运行状态",
              description: "模型与基础服务的真实状态",
            },
          ].map((n) => (
            <button
              className="quick-link"
              key={n.page}
              onClick={() => go(n.page as Page)}
            >
              <n.icon size={21} />
              <div>
                <strong>{n.title}</strong>
                <small>{n.description}</small>
              </div>
              <ChevronRight size={16} />
            </button>
          ))}
          <div className="session-count">
            已建立 <strong>{stats.data?.conversations ?? "—"}</strong>{" "}
            个问答会话
          </div>
        </section>
      </div>
    </>
  );
}
