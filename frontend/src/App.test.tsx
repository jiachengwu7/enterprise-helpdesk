import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import App from "./App";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  sessionStorage.clear();
});
it("shows a backend login error without entering the workbench", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(JSON.stringify({ code: 401, message: "用户名或密码错误" }), {
        status: 401,
      }),
  );
  render(<App />);
  fireEvent.change(screen.getByLabelText("用户名"), {
    target: { value: "employee" },
  });
  fireEvent.change(screen.getByLabelText("密码"), {
    target: { value: "wrong" },
  });
  fireEvent.click(screen.getByRole("button", { name: "登录工作台" }));
  expect(await screen.findByRole("alert")).toHaveProperty(
    "textContent",
    "用户名或密码错误",
  );
  expect(screen.queryByRole("navigation")).toBeNull();
});
it("restores employees without exposing administrator navigation", async () => {
  sessionStorage.setItem("helpdesk-token", "session");
  vi.stubGlobal(
    "fetch",
    async (url: string) =>
      new Response(
        JSON.stringify({
          code: 200,
          data: url.endsWith("/auth/me")
            ? {
                id: "employee",
                username: "employee",
                displayName: "员工",
                departmentId: "IT",
                role: "EMPLOYEE",
              }
            : url.endsWith("/dashboard")
              ? {
                  documents: 2,
                  tickets: 3,
                  openTickets: 1,
                  resolvedTickets: 1,
                  conversations: 4,
                }
              : url.endsWith("/departments")
                ? [{ id: "IT", name: "信息技术部" }]
                : [],
        }),
      ),
  );
  render(<App />);
  await waitFor(() => expect(screen.getByRole("navigation")).toBeTruthy());
  expect(screen.queryByRole("button", { name: "用户管理" })).toBeNull();
  expect(screen.getByRole("button", { name: "知识助手" })).toBeTruthy();
});
