import type { User, Ticket } from "./types";
// 这些规则只决定显示哪些操作按钮；真正的授权在 Java 服务端，隐藏按钮不能替代权限校验。
// AGENT 是处理工单的人类服务专员角色，与 LangGraph 中的 AI 流程不是同一个概念。
/**
 * 用途：决定界面是否显示文档管理操作，后端仍独立鉴权。
 * 输入：user：用户；doc：含所属部门的文档。
 * 输出：布尔值。
 */
export function canManageDocument(user: User, doc: { departmentId: string }) {
  return (
    user.role === "ADMIN" ||
    (user.role === "AGENT" && user.departmentId === doc.departmentId)
  );
}
/**
 * 用途：根据角色、归属和状态计算可用按钮。
 * 输入：user：用户；ticket：工单。
 * 输出：start、resolve、close、assign 布尔字段对象。
 */
export function ticketActions(user: User, ticket: Ticket) {
  const staff =
    user.role === "ADMIN" ||
    (user.role === "AGENT" && user.departmentId === ticket.departmentId);
  return {
    start: staff && ticket.status === "OPEN",
    resolve: staff && ticket.status === "IN_PROGRESS",
    close:
      ticket.status === "RESOLVED" &&
      (ticket.creatorId === user.id || user.role === "ADMIN"),
    assign: staff && ticket.status !== "CLOSED",
  };
}
