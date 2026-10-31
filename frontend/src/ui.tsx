import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { AlertCircle, Loader2, RefreshCw, X, Inbox } from "lucide-react";
import { errorMessage } from "./api";
/**
 * 用途：复用异步加载状态并防止旧响应覆盖新结果。
 * 输入：loader：异步加载函数；dependencies：重新加载依赖。
 * 输出：data、setData、loading、error、reload 对象。
 */
export function useLoad<T>(
  loader: () => Promise<T>,
  dependencies: unknown[] = [],
) {
  // 自定义 Hook 复用加载/错误/刷新状态；请求序号只允许最新响应更新 UI。
  // 清理时递增序号会忽略旧响应，但不会取消已经发出的网络请求。
  const [data, setData] = useState<T | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState("");
  const request = useRef(0);
  /**
   * 用途：执行新一轮加载，仅让最新请求结果更新界面。
   * 输入：无显式参数，使用 Hook 捕获的 loader。
   * 输出：Promise<void>；更新 data、loading、error 状态，不直接返回业务数据。
   */
  const reload = useCallback(async () => {
    const id = ++request.current;
    setLoading(true);
    setError("");
    try {
      const result = await loader();
      if (id === request.current) setData(result);
    } catch (e) {
      if (id === request.current) setError(errorMessage(e));
    } finally {
      if (id === request.current) setLoading(false);
    }
  }, dependencies);
  useEffect(() => {
    void reload();
    return () => {
      request.current++;
    };
  }, [reload]);
  return { data, setData, loading, error, reload };
}
/**
 * 用途：展示错误和可选重试操作。
 * 输入：message：提示；retry：可选重试回调。
 * 输出：错误提示 JSX；无消息时 null。
 */
export function ErrorBox({
  message,
  retry,
}: {
  message: string;
  retry?: () => void;
}) {
  return message ? (
    <div role="alert" className="error-box">
      <AlertCircle size={17} />
      <span>{message}</span>
      {retry && (
        <button className="text-button" onClick={retry}>
          重试
        </button>
      )}
    </div>
  ) : null;
}
/**
 * 用途：渲染加载占位。
 * 输入：无参数。
 * 输出：加载指示 JSX。
 */
export function Loading() {
  return (
    <div className="empty">
      <Loader2 className="spin" size={24} />
      <p>正在加载…</p>
    </div>
  );
}
/**
 * 用途：渲染空数据状态。
 * 输入：title、description：提示；children：可选操作内容。
 * 输出：空状态 JSX。
 */
export function Empty({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <Inbox size={28} />
      </div>
      <h3>{title}</h3>
      {description && <p>{description}</p>}
      {children}
    </div>
  );
}
/**
 * 用途：渲染页面标题和右侧操作区。
 * 输入：eyebrow、title、description：标题文本；children：操作内容。
 * 输出：标题区域 JSX。
 */
export function PageTitle({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-title">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="actions">{children}</div>
    </div>
  );
}
/**
 * 用途：渲染带忙碌状态的刷新按钮。
 * 输入：onClick：刷新回调；busy：是否加载中。
 * 输出：刷新按钮 JSX。
 */
export function Refresh({
  onClick,
  busy = false,
}: {
  onClick: () => void;
  busy?: boolean;
}) {
  return (
    <button className="button secondary" onClick={onClick} disabled={busy}>
      <RefreshCw size={15} className={busy ? "spin" : ""} />
      刷新
    </button>
  );
}
/**
 * 用途：提供对话框、焦点约束及关闭交互。
 * 输入：title、children：内容；onClose：关闭回调；wide：宽版开关。
 * 输出：对话框 JSX；挂载时绑定键盘事件，卸载清理。
 */
export function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const previous = useRef<HTMLElement | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    previous.current = document.activeElement as HTMLElement;
    ref.current?.focus();
    /**
     * 用途：处理对话框 Escape 关闭与 Tab 焦点循环。
     * 输入：e：键盘事件。
     * 输出：void；按需关闭弹窗或移动焦点。
     */
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      if (e.key === "Tab") {
        const focusable = Array.from(
          ref.current?.querySelectorAll<HTMLElement>(
            'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]',
          ) || [],
        );
        if (focusable.length === 0) return;
        const first = focusable[0],
          last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", handler);
    return () => {
      document.removeEventListener("keydown", handler);
      previous.current?.focus();
    };
  }, []);
  return (
    <div className="modal-backdrop">
      <div
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`modal ${wide ? "wide" : ""}`}
      >
        <div className="modal-heading">
          <h2>{title}</h2>
          <button
            aria-label="关闭弹窗"
            className="icon-button"
            onClick={onClose}
          >
            <X size={21} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
export const labels: Record<string, string> = {
  ADMIN: "管理员",
  AGENT: "处理员",
  EMPLOYEE: "员工",
  OPEN: "待处理",
  IN_PROGRESS: "处理中",
  RESOLVED: "已解决",
  CLOSED: "已关闭",
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  PUBLIC: "全员可见",
  DEPARTMENT: "部门可见",
  QUEUED: "排队中",
  PROCESSING: "索引中",
  READY: "已就绪",
  FAILED: "索引失败",
};
/**
 * 用途：将状态值映射为中文标签与样式。
 * 输入：value：状态字符串。
 * 输出：状态徽标 JSX。
 */
export function Badge({ value }: { value: string }) {
  return (
    <span className={`badge ${value.toLowerCase()}`}>
      {labels[value] || value}
    </span>
  );
}
/**
 * 用途：将时间字符串转换为本地简短显示。
 * 输入：value：时间字符串。
 * 输出：格式化日期文本。
 */
export function formatDate(value: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";
}
/**
 * 用途：统一渲染表单字段标题与控件。
 * 输入：label：字段名；children：输入控件。
 * 输出：字段容器 JSX。
 */
export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}
