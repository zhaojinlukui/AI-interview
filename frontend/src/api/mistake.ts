import { request } from './request';

export type MistakeSourceType = 'TEXT' | 'VOICE';

export interface MistakeQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  topicSummary?: string | null;
  userAnswer?: string | null;
  score: number | null;
  feedback?: string | null;
  referenceAnswer?: string | null;
  keyPoints?: string[] | null;
}

export interface MistakeSession {
  sourceType: MistakeSourceType;
  sourceSessionId: string;
  title: string;
  createdAt: string;
  overallScore: number | null;
  mistakeCount: number;
  mistakes: MistakeQuestion[];
  practiceType?: string | null;
}

export interface CreateMistakePracticeResponse {
  sessionId: string;
}

export const mistakeApi = {
  async listMistakes(): Promise<MistakeSession[]> {
    return request.get<MistakeSession[]>('/api/interview/mistakes');
  },

  async getMistakeSession(sourceType: MistakeSourceType, sourceSessionId: string): Promise<MistakeSession> {
    return request.get<MistakeSession>(
      `/api/interview/mistakes/${sourceType}/${sourceSessionId}`
    );
  },

  async createPractice(
    sourceType: MistakeSourceType,
    sourceSessionId: string
  ): Promise<CreateMistakePracticeResponse> {
    return request.post<CreateMistakePracticeResponse>(
      `/api/interview/mistakes/${sourceType}/${sourceSessionId}/practice`
    );
  },
};
