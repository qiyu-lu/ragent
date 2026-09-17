import type { CandidateTaskTemplate } from "@/types";

import { api } from "./api";

export const getTaskTemplatesByMessage = async (
  sourceMessageId: string
): Promise<CandidateTaskTemplate[]> => {
  return api.get<CandidateTaskTemplate[], CandidateTaskTemplate[]>(
    `/iron-ore/messages/${sourceMessageId}/task-templates`
  );
};

export const createTaskTemplate = async (
  sourceMessageId: string,
  docId: string
): Promise<CandidateTaskTemplate> => {
  return api.post<CandidateTaskTemplate, CandidateTaskTemplate>("/iron-ore/task-templates", {
    sourceMessageId,
    docId
  });
};

export const approveTaskTemplate = async (taskId: string): Promise<CandidateTaskTemplate> => {
  return api.post<CandidateTaskTemplate, CandidateTaskTemplate>(
    `/iron-ore/task-templates/${taskId}/approve`
  );
};
