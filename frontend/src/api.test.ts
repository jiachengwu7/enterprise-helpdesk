import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, setToken } from "./api";
afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});
describe("browser API boundary", () => {
  it("unwraps envelopes and sends the session bearer token", async () => {
    setToken("session-value");
    let actual: RequestInit | undefined;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      actual = init;
      return new Response(
        JSON.stringify({ code: 200, message: "success", data: { id: "abc" } }),
      );
    });
    expect(await api("/auth/me")).toEqual({ id: "abc" });
    expect(new Headers(actual?.headers).get("Authorization")).toBe(
      "Bearer session-value",
    );
  });
  it("rejects real HTTP errors even when an envelope claims success", async () => {
    vi.stubGlobal(
      "fetch",
      async () =>
        new Response(
          JSON.stringify({ code: 200, message: "拒绝访问", data: null }),
          { status: 403 },
        ),
    );
    await expect(api("/documents")).rejects.toMatchObject({
      status: 403,
      message: "拒绝访问",
    });
  });
  it("reports proxy offline errors without JSON parsing noise", async () => {
    vi.stubGlobal(
      "fetch",
      async () => new Response("proxy error", { status: 502 }),
    );
    await expect(api("/health")).rejects.toBeInstanceOf(ApiError);
  });
  it("clears an expired session and emits auth-expired", async () => {
    setToken("expired");
    const listener = vi.fn();
    window.addEventListener("auth-expired", listener);
    vi.stubGlobal(
      "fetch",
      async () =>
        new Response(JSON.stringify({ code: 401, message: "登录已过期" }), {
          status: 401,
        }),
    );
    await expect(api("/auth/me")).rejects.toMatchObject({ status: 401 });
    expect(sessionStorage.getItem("helpdesk-token")).toBeNull();
    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener("auth-expired", listener);
  });
  it("does not force JSON content type over multipart", async () => {
    let headers: Headers | undefined;
    vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
      headers = new Headers(init.headers);
      return new Response(JSON.stringify({ code: 200, data: null }));
    });
    await api("/documents", { method: "POST", body: new FormData() });
    expect(headers?.has("Content-Type")).toBe(false);
  });
});
