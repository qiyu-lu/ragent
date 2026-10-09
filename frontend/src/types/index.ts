export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export type PersistedMessageStatus = "NORMAL" | "INTERRUPTED" | "REJECTED";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface SourceRef {
  evidenceId?: string;
  sourceLocation?: Record<string, unknown>;
  sourceExtent?: string;
  truncated?: boolean;
  index?: number;
  docId: string;
  docName?: string;
  sourceType?: string;
  fileType?: string | null;
  url?: string | null;
  excerpt?: string;
  chunkId?: string;
  documentVersion?: string;
  sheetName?: string;
  cellRange?: string;
  /** 标准号（文档元数据） */
  standardNo?: string;
  /** 代替本文档的新版本标准号；有值即"已被代替" */
  supersededBy?: string;
  /** 解析质量审计：RECOVERED（OCR 重解析后合格）/ NEEDS_REVIEW（需人工复核） */
  parseVerdict?: "RECOVERED" | "NEEDS_REVIEW";
}

export interface Message {
  researchRunId?: string;
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  sources?: SourceRef[];
  recommended?: string[];
  recommendedState?: "loading" | "ready" | "error";
  recommendedOpen?: boolean;
  messageStatus?: PersistedMessageStatus;
}

export type RecommendedQuestionStatus = "SUCCESS" | "EMPTY" | "FAILED";

export interface RecommendedQuestionsPayload {
  status: RecommendedQuestionStatus;
  questions: string[];
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
  sources?: SourceRef[];
  messageStatus?: PersistedMessageStatus;
}
