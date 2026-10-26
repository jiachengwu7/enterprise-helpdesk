import { describe, expect, it } from "vitest";
import { canManageDocument, ticketActions } from "./permissions";
import type { User, Ticket } from "./types";
const employee: User = {
  id: "u1",
  username: "e",
  displayName: "员工",
  departmentId: "IT",
  role: "EMPLOYEE",
};
const ticket = {
  creatorId: "u1",
  departmentId: "IT",
  status: "RESOLVED",
} as Ticket;
describe("role-aware actions", () => {
  it("employees cannot manage even public documents", () =>
    expect(canManageDocument(employee, { departmentId: "IT" })).toBe(false));
  it("agents cannot replace another department document", () =>
    expect(
      canManageDocument({ ...employee, role: "AGENT" }, { departmentId: "HR" }),
    ).toBe(false));
  it("only creator or admin can close a resolved ticket", () => {
    expect(ticketActions(employee, ticket).close).toBe(true);
    expect(
      ticketActions({ ...employee, id: "u2", role: "AGENT" }, ticket).close,
    ).toBe(false);
  });
  it("staff transitions follow the lifecycle and department", () => {
    expect(
      ticketActions(
        { ...employee, role: "AGENT" },
        { ...ticket, status: "OPEN" },
      ).start,
    ).toBe(true);
    expect(
      ticketActions(
        { ...employee, role: "AGENT", departmentId: "HR" },
        { ...ticket, status: "OPEN" },
      ).start,
    ).toBe(false);
    expect(ticketActions(employee, { ...ticket, status: "OPEN" }).start).toBe(
      false,
    );
    expect(ticketActions({ ...employee, role: "ADMIN" }, ticket).resolve).toBe(
      false,
    );
  });
});
