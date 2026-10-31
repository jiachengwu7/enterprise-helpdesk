export class ApiError extends Error {
  /**
   * 用途：保存请求错误的提示和 HTTP 状态。
   * 输入：message：提示；status：状态码，0 表示连接失败。
   * 输出：构造函数，无返回值；创建错误实例。
   */
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
/**
 * 用途：保存或清除当前标签页的登录令牌。
 * 输入：value：令牌或 null。
 * 输出：void；更新 sessionStorage。
 */
export function setToken(value: string | null) {
  // 令牌保存在当前标签页的 sessionStorage，所有 API 请求统一通过下方 request 携带。
  if (value) sessionStorage.setItem("helpdesk-token", value);
  else sessionStorage.removeItem("helpdesk-token");
}
/**
 * 用途：读取当前标签页的令牌。
 * 输入：无参数。
 * 输出：令牌字符串或 null。
 */
export function getToken() {
  return sessionStorage.getItem("helpdesk-token");
}
/**
 * 用途：统一添加认证头并发送 Java API 请求。
 * 输入：path：业务路径；options：可选 fetch 配置。
 * 输出：Promise<Response>；401 时清除令牌并发登录过期事件。
 */
async function request(path: string, options: RequestInit = {}) {
  // 浏览器只调用 Java /api；Python 内部服务令牌和云端模型 Key 都留在服务端。
  const headers = new Headers(options.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !(options.body instanceof FormData))
    // 上传文件时让浏览器自动设置 multipart boundary，手动指定 Content-Type 会破坏边界。
    headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...options, headers });
  } catch {
    throw new ApiError("无法连接服务，请检查本机服务是否启动。", 0);
  }
  if (response.status === 401) {
    // 统一通知 App 登录已过期，避免各个页面分别维护互相不一致的登录状态。
    setToken(null);
    window.dispatchEvent(new Event("auth-expired"));
  }
  return response;
}
/**
 * 用途：解开统一 JSON 响应并处理错误状态。
 * 输入：path：业务路径；options：可选请求配置。
 * 输出：Promise<T> 业务数据；失败抛 ApiError。
 */
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await request(path, options);
  let result: { code: number; message: string; data: T };
  // 解开 Java 统一响应包裹，让页面只处理业务数据；泛型 T 是静态类型，不是运行时校验。
  try {
    result = await response.json();
  } catch {
    throw new ApiError(
      `服务暂不可用（HTTP ${response.status}），请检查后台服务。`,
      response.status,
    );
  }
  if (!response.ok || result.code !== 200)
    throw new ApiError(
      result.message || "请求失败，请稍后重试。",
      response.status,
    );
  return result.data;
}
/**
 * 用途：以 JSON POST 形式调用业务接口。
 * 输入：path：路径；data：请求对象。
 * 输出：Promise<T> 业务结果。
 */
export function post<T = unknown>(path: string, data: unknown) {
  return api<T>(path, { method: "POST", body: JSON.stringify(data) });
}
/**
 * 用途：带认证下载原件并触发浏览器保存。
 * 输入：id：文档 ID；filename：下载文件名。
 * 输出：Promise<void>；创建后释放临时下载 URL。
 */
export async function downloadDocument(id: string, filename: string) {
  const response = await request(`/documents/${id}/download`);
  if (!response.ok) {
    let message = "原件下载失败";
    try {
      message = (await response.json()).message || message;
    } catch {}
    throw new ApiError(message, response.status);
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
/**
 * 用途：将未知错误统一转换为用户提示。
 * 输入：error：未知类型的错误。
 * 输出：错误消息字符串。
 */
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : "操作失败，请重试。";
