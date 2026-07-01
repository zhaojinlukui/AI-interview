import type { InterviewDetail, ResumeDetail, ResumeListItem } from '../api/history';
import type { VoiceEvaluationDetail } from '../api/voiceInterview';
import type { UserRole } from './auth';

export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  updatedAt?: string | null;
  lastLoginAt?: string | null;
  resumeCount: number;
  textInterviewCount: number;
  voiceInterviewCount: number;
  recentActivityAt?: string | null;
}

export type AdminInterviewType = 'TEXT' | 'VOICE';

export interface AdminInterviewItem {
  type: AdminInterviewType;
  id: number;
  sessionId: string;
  skillId?: string | null;
  difficulty?: string | null;
  totalQuestions?: number | null;
  overallScore?: number | null;
  status?: string | null;
  evaluateStatus?: string | null;
  evaluateError?: string | null;
  createdAt?: string | null;
  completedAt?: string | null;
}

export interface AdminVoiceInterviewDetail {
  sessionId: number;
  roleType?: string | null;
  skillId?: string | null;
  difficulty?: string | null;
  status?: string | null;
  evaluateStatus?: string | null;
  evaluateError?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  updatedAt?: string | null;
  evaluation?: VoiceEvaluationDetail | null;
}

export interface ResumeWeightsParameters {
  projectWeight: number;
  skillMatchWeight: number;
  contentWeight: number;
  structureWeight: number;
  expressionWeight: number;
}

export interface InterviewParameters {
  resumeQuestionRatio: number;
  directionQuestionRatio: number;
  questionTemperature: number;
  followUpTemperature: number;
  scoringTemperature: number;
  commentTemperature: number;
}

export interface RagSearchParameters {
  topkShort: number;
  topkMedium: number;
  topkLong: number;
  minScoreShort: number;
  minScoreMedium: number;
  minScoreLong: number;
}

export interface SystemAiParameters {
  resumeWeights: ResumeWeightsParameters;
  interview: InterviewParameters;
  ragSearch: RagSearchParameters;
}

export type AdminResumeListItem = ResumeListItem;
export type AdminResumeDetail = ResumeDetail;
export type AdminTextInterviewDetail = InterviewDetail;
