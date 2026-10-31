import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import Assistant from "./Assistant";
import type { Source } from "./types";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it.each([
  {
    content: "员工提交请假审批[1]。审批后同步考勤[1]。",
    titles: ["HR请假与考勤"],
    numbers: ["1"],
  },
  { content: "工单处理流程见[2]。", titles: ["公共帮助中心"], numbers: ["2"] },
  {
    content: "仅检索，未调用模型。\n[1] 请假审批原文\n[2] 工单流程原文",
    titles: ["HR请假与考勤", "公共帮助中心"],
    numbers: ["1", "2"],
  },
  { content: "当前资料不足以回答。", titles: [], numbers: [] },
  { content: "无效编号[0][99]不能指向现有资料。", titles: [], numbers: [] },
])(
  "shows only cited sources with their original numbers: $content",
  async ({ content, titles, numbers }) => {
    const sources: Source[] = [
      {
        documentId: "hr-doc",
        title: "HR请假与考勤",
        filename: "hr.md",
        chunkIndex: 0,
        content: "提交直属主管审批",
        score: 0.03,
        version: 1,
      },
      {
        documentId: "help-doc",
        title: "公共帮助中心",
        filename: "help.md",
        chunkIndex: 0,
        content: "工单分配与处理",
        score: 0.01,
        version: 1,
      },
    ];
    const conversation = {
      id: "c-history",
      title: "请假咨询",
      createdAt: "2026-10-28T10:00:00Z",
      updatedAt: "2026-10-28T10:00:00Z",
    };
    vi.stubGlobal("fetch", async (url: string) => {
      let data: unknown;
      if (url.endsWith("/system/status"))
        data = {
          ai: { status: "ok", modelConfigured: true },
          database: "ok",
          version: "1.0.0",
        };
      else if (url.endsWith("/conversations")) data = [conversation];
      else if (url.endsWith("/conversations/c-history"))
        data = {
          ...conversation,
          messages: [
            {
              id: "m-history",
              role: "assistant",
              content,
              sources,
              proposal: null,
              createdAt: conversation.createdAt,
            },
          ],
          pendingProposal: null,
        };
      else throw new Error(`Unexpected request: ${url}`);
      return new Response(
        JSON.stringify({ code: 200, message: "success", data }),
      );
    });
    render(<Assistant openTicket={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: /^请假咨询/ }));
    await screen.findByText(content, { normalizer: (value) => value });
    for (const source of sources) {
      const card = screen.queryByRole("button", {
        name: new RegExp(source.title),
      });
      if (titles.includes(source.title)) expect(card).not.toBeNull();
      else expect(card).toBeNull();
    }
    if (titles.length) {
      expect(screen.getByText(`参考来源 · ${titles.length}`)).toBeTruthy();
      titles.forEach((title, index) => {
        const card = screen.getByRole("button", { name: new RegExp(title) });
        expect(within(card).getByText(numbers[index])).toBeTruthy();
      });
      fireEvent.click(
        screen.getByRole("button", { name: new RegExp(titles[0]) }),
      );
      expect(
        screen.getByText(
          titles[0] === "HR请假与考勤" ? "提交直属主管审批" : "工单分配与处理",
        ),
      ).toBeTruthy();
    } else expect(screen.queryByText(/参考来源 ·/)).toBeNull();
  },
);
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
