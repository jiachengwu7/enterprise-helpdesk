import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import Assistant from "./Assistant";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("does not label an unreachable AI service as working retrieval mode", async () => {
  vi.stubGlobal(
    "fetch",
    async (url: string) =>
      new Response(
        JSON.stringify({
          code: 200,
          data: url.endsWith("/system/status")
            ? {
                ai: { status: "unavailable" },
                database: "ok",
                version: "1.0.0",
              }
            : [],
        }),
      ),
  );
  render(<Assistant openTicket={() => {}} />);
  expect(
    await screen.findByText("AI 服务不可用 · 请检查系统状态"),
  ).toBeTruthy();
  expect(screen.queryByText("仅检索模式 · 未调用模型")).toBeNull();
});
it("recovers a newly created conversation after an uncertain send failure so pending approval is visible", async () => {
  const proposal = {
    id: "p1",
    title: "网络支持",
    description: "检查工作电脑联网",
    priority: "MEDIUM",
  };
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    let data: unknown = [];
    if (url.endsWith("/system/status"))
      data = {
        ai: { status: "ok", modelConfigured: false },
        database: "ok",
        version: "1.0.0",
      };
    if (url.endsWith("/conversations") && init.method === "POST")
      data = {
        id: "c1",
        title: "网络支持",
        createdAt: "2026-10-26T10:00:00Z",
        updatedAt: "2026-10-26T10:00:00Z",
      };
    if (url.endsWith("/messages"))
      return new Response(
        JSON.stringify({
          code: 503,
          message: "响应中断，请刷新会话确认处理结果",
        }),
        { status: 503 },
      );
    if (url.endsWith("/conversations/c1"))
      data = {
        id: "c1",
        title: "网络支持",
        messages: [
          {
            id: "m1",
            role: "assistant",
            content: "请确认草稿",
            sources: [],
            proposal,
            createdAt: "2026-10-26T10:00:00Z",
          },
        ],
        pendingProposal: proposal,
      };
    return new Response(
      JSON.stringify({ code: 200, message: "success", data }),
    );
  });
  render(<Assistant openTicket={() => {}} />);
  fireEvent.change(screen.getByLabelText("输入问题"), {
    target: { value: "无法联网，请生成工单" },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送问题" }));
  expect(
    await screen.findByRole("button", { name: "确认创建工单" }),
  ).toBeTruthy();
  expect(screen.getByRole("alert").textContent).toContain("响应中断");
});
