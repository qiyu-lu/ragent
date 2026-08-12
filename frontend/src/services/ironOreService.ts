import type { CandidateTaskTemplate, RobotMission, TaskExecution } from "@/types";

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

export const simulateTaskTemplate = async (taskId: string): Promise<TaskExecution> => {
  return api.post<TaskExecution, TaskExecution>(`/iron-ore/task-templates/${taskId}/simulate`);
};

export const getRobotMission = async (taskId: string): Promise<RobotMission | null> => {
  return api.get<RobotMission | null, RobotMission | null>(
    `/iron-ore/task-templates/${taskId}/robot-mission`
  );
};

export const dispatchRobotMission = async (taskId: string): Promise<RobotMission> => {
  return api.post<RobotMission, RobotMission>(`/iron-ore/task-templates/${taskId}/robot-missions`, {
    missionType: "SAMPLE_TRANSPORT",
    robotId: "robot-demo-01",
    containerId: "sample_bucket_01",
    sourceStationId: "sampling_area",
    targetStationId: "center_laboratory",
    homeStationId: "home",
    returnHome: true,
    dryRun: true
  });
};

export const cancelRobotMission = async (missionId: string): Promise<RobotMission> => {
  return api.post<RobotMission, RobotMission>(`/iron-ore/robot-missions/${missionId}/cancel`);
};
