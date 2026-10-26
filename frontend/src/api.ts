export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export function setToken(value: string | null) {
  if (value) sessionStorage.setItem("helpdesk-token", value);
  else sessionStorage.removeItem("helpdesk-token");
}
export function getToken() {
  return sessionStorage.getItem("helpdesk-token");
}
async function request(path: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...options, headers });
  } catch {
    throw new ApiError("无法连接服务，请检查本机服务是否启动。", 0);
  }
  if (response.status === 401) {
    setToken(null);
    window.dispatchEvent(new Event("auth-expired"));
  }
  return response;
}
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await request(path, options);
  let result: { code: number; message: string; data: T };
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
export function post<T = unknown>(path: string, data: unknown) {
  return api<T>(path, { method: "POST", body: JSON.stringify(data) });
}
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
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : "操作失败，请重试。";
