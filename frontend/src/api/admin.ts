import { request } from './request';
import type {
  AdminInterviewItem,
  AdminResumeDetail,
  AdminResumeListItem,
  AdminTextInterviewDetail,
  AdminUser,
  AdminVoiceInterviewDetail,
  SystemAiParameters,
} from '../types/admin';

async function downloadBlob(url: string): Promise<Blob> {
  const response = await request.getInstance().get(url, {
    responseType: 'blob',
    skipResultTransform: true,
  } as never);
  return response.data;
}

export const adminApi = {
  listUsers(): Promise<AdminUser[]> {
    return request.get<AdminUser[]>('/api/admin/users');
  },

  updateDisplayName(id: number, displayName: string): Promise<void> {
    return request.put<void>(`/api/admin/users/${id}/display-name`, { displayName });
  },

  updatePassword(id: number, newPassword: string): Promise<void> {
    return request.put<void>(`/api/admin/users/${id}/password`, { newPassword });
  },

  listUserResumes(id: number): Promise<AdminResumeListItem[]> {
    return request.get<AdminResumeListItem[]>(`/api/admin/users/${id}/resumes`);
  },

  getResumeDetail(resumeId: number): Promise<AdminResumeDetail> {
    return request.get<AdminResumeDetail>(`/api/admin/resumes/${resumeId}/detail`);
  },

  exportResumePdf(resumeId: number): Promise<Blob> {
    return downloadBlob(`/api/admin/resumes/${resumeId}/export`);
  },

  listUserInterviews(id: number): Promise<AdminInterviewItem[]> {
    return request.get<AdminInterviewItem[]>(`/api/admin/users/${id}/interviews`);
  },

  getTextInterviewDetail(sessionId: string): Promise<AdminTextInterviewDetail> {
    return request.get<AdminTextInterviewDetail>(`/api/admin/interviews/text/${sessionId}`);
  },

  exportTextInterviewPdf(sessionId: string): Promise<Blob> {
    return downloadBlob(`/api/admin/interviews/text/${sessionId}/export`);
  },

  getVoiceInterviewDetail(sessionId: number): Promise<AdminVoiceInterviewDetail> {
    return request.get<AdminVoiceInterviewDetail>(`/api/admin/interviews/voice/${sessionId}`);
  },

  getAiParameters(): Promise<SystemAiParameters> {
    return request.get<SystemAiParameters>('/api/admin/ai-parameters');
  },

  updateAiParameters(data: SystemAiParameters): Promise<void> {
    return request.put<void>('/api/admin/ai-parameters', data);
  },
};
