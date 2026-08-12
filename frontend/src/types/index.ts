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
}

export type CandidateTaskStatus = "DRAFT" | "APPROVED" | "SIMULATED";

export interface TaskEvidenceItem {
  text: string;
  evidenceChunkIds: string[];
}

export interface TaskParameter {
  name: string;
  value: string;
  unit?: string | null;
}

export interface CandidateTaskStep {
  order: number;
  action: string;
  tools: string[];
  parameters: TaskParameter[];
  evidenceChunkIds: string[];
}

export interface TaskTemplatePayload {
  title: string;
  procedureName: string;
  documentVersion?: string | null;
  prerequisites: TaskEvidenceItem[];
  steps: CandidateTaskStep[];
  qualityCriteria: TaskEvidenceItem[];
  exceptionHandling: TaskEvidenceItem[];
  safetyConstraints: TaskEvidenceItem[];
}

export interface TaskEvidenceRef {
  chunkId: string;
  docId: string;
  docName?: string | null;
  documentVersion?: string | null;
  sheetName?: string | null;
  cellRange?: string | null;
  excerpt?: string | null;
}

export interface TaskSimulationEvent {
  sequence: number;
  type: string;
  message: string;
  evidenceChunkIds: string[];
}

export interface TaskExecution {
  id: string;
  taskTemplateId: string;
  status: string;
  events: TaskSimulationEvent[];
  startTime?: string | null;
  endTime?: string | null;
}

export interface CandidateTaskTemplate {
  id: string;
  conversationId: string;
  sourceMessageId: string;
  docId: string;
  status: CandidateTaskStatus;
  template: TaskTemplatePayload;
  evidenceRefs: TaskEvidenceRef[];
  execution?: TaskExecution | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  createTime?: string | null;
}

export type RobotMissionStatus =
  | "READY"
  | "DISPATCHED"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELED"
  | "DISPATCH_FAILED";

export interface RobotSkillStep {
  order: number;
  skillId: "NAVIGATE_TO_STATION" | "TRANSPORT_CONTAINER";
  parameters: Record<string, string>;
  timeoutSeconds: number;
}

export interface RobotMissionPayload {
  missionId: string;
  taskTemplateId: string;
  documentVersion?: string | null;
  missionType: "SAMPLE_TRANSPORT";
  robotId: string;
  dryRun: true;
  scope: string;
  planHash: string;
  steps: RobotSkillStep[];
}

export interface RobotMissionEvent {
  sequence: number;
  status: string;
  skillId?: string | null;
  message: string;
  timestamp: string;
}

export interface RobotMission {
  id: string;
  taskTemplateId: string;
  robotId: string;
  status: RobotMissionStatus;
  planHash: string;
  mission: RobotMissionPayload;
  currentStep: number;
  totalSteps: number;
  currentSkillId?: string | null;
  message?: string | null;
  events: RobotMissionEvent[];
  dispatchedAt?: string | null;
  completedAt?: string | null;
  createTime?: string | null;
}

export interface Message {
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
