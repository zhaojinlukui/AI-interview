import axios from 'axios';
import { buildAuthHeaders, request, resolveApiUrl } from './request';

export type VectorStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
  vectorStatus: VectorStatus;
  vectorError: string | null;
  chunkCount: number;
}

export interface KnowledgeBaseStats {
  totalCount: number;
  totalQuestionCount: number;
  completedCount: number;
  processingCount: number;
}

export type SortOption = 'time' | 'size' | 'question';

export interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    fileSize: number;
    contentLength: number;
    vectorStatus: VectorStatus;
  };
  storage: {
    fileKey: string;
    fileUrl: string;
  };
  duplicate: boolean;
}

export interface UploadKnowledgeBaseBatchItemResponse {
  filename: string;
  success: boolean;
  result: UploadKnowledgeBaseResponse | null;
  errorMessage: string | null;
}

export interface UploadKnowledgeBaseBatchResponse {
  items: UploadKnowledgeBaseBatchItemResponse[];
  totalCount: number;
  successCount: number;
  failureCount: number;
  duplicateCount: number;
}

export const knowledgeBaseApi = {
  async uploadKnowledgeBases(
    files: File[],
    names?: string[]
  ): Promise<UploadKnowledgeBaseBatchResponse> {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    if (names?.length) {
      files.forEach((_, index) => formData.append('names', names[index] ?? ''));
    }
    return request.upload<UploadKnowledgeBaseBatchResponse>('/api/knowledgebase/upload/batch', formData);
  },

  async downloadKnowledgeBase(id: number): Promise<Blob> {
    const response = await axios.get(resolveApiUrl(`/api/knowledgebase/${id}/download`), {
      responseType: 'blob',
      headers: buildAuthHeaders(),
    });
    return response.data;
  },

  async getAllKnowledgeBases(
    sortBy?: SortOption,
    vectorStatus?: VectorStatus
  ): Promise<KnowledgeBaseItem[]> {
    const params = new URLSearchParams();
    if (sortBy) {
      params.append('sortBy', sortBy);
    }
    if (vectorStatus) {
      params.append('vectorStatus', vectorStatus);
    }
    const queryString = params.toString();
    return request.get<KnowledgeBaseItem[]>(
      `/api/knowledgebase/list${queryString ? `?${queryString}` : ''}`
    );
  },

  async deleteKnowledgeBase(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/${id}`);
  },

  async search(keyword: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(
      `/api/knowledgebase/search?keyword=${encodeURIComponent(keyword)}`
    );
  },

  async getStatistics(): Promise<KnowledgeBaseStats> {
    return request.get<KnowledgeBaseStats>('/api/knowledgebase/stats');
  },

  async revectorize(id: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/revectorize`);
  },
};
