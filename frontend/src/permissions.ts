import type { User, Ticket } from "./types";
export function canManageDocument(user: User, doc: { departmentId: string }) {
  return (
    user.role === "ADMIN" ||
    (user.role === "AGENT" && user.departmentId === doc.departmentId)
  );
}
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
