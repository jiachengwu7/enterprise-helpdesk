export type Role = "ADMIN" | "AGENT" | "EMPLOYEE";
export type User = {
  id: string;
  username: string;
  displayName: string;
  departmentId: string;
  role: Role;
};
export type Department = { id: string; name: string };
export type Document = {
  id: string;
  title: string;
  filename: string;
  departmentId: string;
  visibility: "PUBLIC" | "DEPARTMENT";
  version: number;
  status: "QUEUED" | "PROCESSING" | "READY" | "FAILED";
  chunkCount: number;
  error: string | null;
  createdAt: string;
  indexMode?: string;
};
export type Source = {
  documentId: string;
  title: string;
  filename: string;
  chunkIndex: number;
  content: string;
  score: number;
  version: number;
};
export type Proposal = {
  id: string;
  title: string;
  description: string;
  priority: "LOW" | "MEDIUM" | "HIGH";
};
export type Message = {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources: Source[];
  proposal: Proposal | null;
  createdAt: string;
};
export type Conversation = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};
export type ConversationDetail = {
  id: string;
  title: string;
  messages: Message[];
  pendingProposal: Proposal | null;
};
export type Answer = {
  answer: string;
  sources: Source[];
  proposal: Proposal | null;
  mode: "llm" | "retrieval-only";
  trace: string[];
};
export type Ticket = {
  id: string;
  number: string;
  title: string;
  description: string;
  priority: "LOW" | "MEDIUM" | "HIGH";
  status: "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";
  departmentId: string;
  creatorId: string;
  creatorName: string;
  assigneeId: string | null;
  assigneeName: string | null;
  resolution: string | null;
  rating: number | null;
  createdAt: string;
  updatedAt: string;
};
export type TicketDetail = Ticket & {
  comments: {
    id: string;
    authorName: string;
    content: string;
    createdAt: string;
  }[];
};
export type Dashboard = {
  documents: number;
  tickets: number;
  openTickets: number;
  resolvedTickets: number;
  conversations: number;
};
export type SystemStatus = {
  ai: Record<string, unknown>;
  database: string;
  version: string;
};
