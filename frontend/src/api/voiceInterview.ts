import { getStoredToken, request } from './request';

export interface CreateSessionRequest {
  roleType?: string;
  skillId: string;
  difficulty?: string;
  customJdText?: string;
  resumeId?: number;
  introEnabled?: boolean;
  techEnabled?: boolean;
  projectEnabled?: boolean;
  hrEnabled?: boolean;
  plannedDuration?: number;
}

export interface SessionResponse {
  sessionId: number;
  roleType: string;
  currentPhase: string;
  status: string;
  startTime: string;
  plannedDuration: number;
  webSocketUrl: string;
}

export interface InterviewMessage {
  id: number;
  sessionId: number;
  messageType: string;
  phase: string;
  userRecognizedText: string;
  aiGeneratedText: string;
  timestamp: string;
  sequenceNum: number;
}

export interface VoiceAnswerDetail {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  referenceAnswer?: string | null;
  keyPoints?: string[] | null;
}

export interface VoiceEvaluationDetail {
  sessionId: number;
  totalQuestions: number;
  overallScore: number;
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  answers: VoiceAnswerDetail[];
}

export interface EvaluationStatusResponse {
  evaluateStatus: string | null;
  evaluateError?: string | null;
  evaluation?: VoiceEvaluationDetail | null;
}

export interface SessionMeta {
  sessionId: number;
  roleType: string;
  status: string;
  currentPhase: string;
  createdAt: string;
  updatedAt: string;
  actualDuration?: number;
  messageCount: number;
  evaluateStatus?: string;
  evaluateError?: string;
  overallScore?: number | null;
}

export interface WebSocketAudioMessage {
  type: 'audio';
  data: string;
  timestamp?: number;
}

export interface WebSocketSubtitleMessage {
  type: 'subtitle';
  text: string;
  isFinal: boolean;
}

export interface WebSocketAudioResponseMessage {
  type: 'audio';
  data: string;
  text: string;
}

export interface WebSocketTextMessage {
  type: 'text';
  content: string;
  final?: boolean;
}

export interface WebSocketAudioChunkMessage {
  type: 'audio_chunk';
  data: string;
  index: number;
  isLast: boolean;
}

export interface WebSocketControlResponseMessage {
  type: 'control';
  action: string;
  message?: string;
  timestamp?: number;
}

export interface WebSocketErrorMessage {
  type: 'error';
  message: string;
}

export type WebSocketMessage =
  | WebSocketAudioMessage
  | WebSocketSubtitleMessage
  | WebSocketAudioResponseMessage
  | WebSocketTextMessage
  | WebSocketAudioChunkMessage
  | WebSocketControlResponseMessage
  | WebSocketErrorMessage;

export interface WebSocketEventHandlers {
  onMessage?: (message: WebSocketMessage) => void;
  onSubtitle?: (text: string, isFinal: boolean) => void;
  onAudioResponse?: (audioData: string, text: string) => void;
  onTextResponse?: (text: string, isFinal: boolean) => void;
  onAudioChunk?: (data: string, index: number, isLast: boolean) => void;
  onControl?: (action: string, message?: string) => void;
  onErrorMessage?: (message: string) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (error: Event) => void;
}

export const voiceInterviewApi = {
  async createSession(data: CreateSessionRequest): Promise<SessionResponse> {
    return request.post<SessionResponse>('/api/voice-interview/sessions', data);
  },

  async endSession(sessionId: number): Promise<void> {
    return request.post<void>(`/api/voice-interview/sessions/${sessionId}/end`);
  },

  async getMessages(sessionId: number): Promise<InterviewMessage[]> {
    return request.get<InterviewMessage[]>(
      `/api/voice-interview/sessions/${sessionId}/messages`
    );
  },

  async getEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.get<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  async generateEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.post<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  async pauseSession(sessionId: number, reason: string = 'user_initiated'): Promise<void> {
    return request.put(
      `/api/voice-interview/sessions/${sessionId}/pause`,
      { reason }
    );
  },

  async resumeSession(sessionId: number): Promise<SessionResponse> {
    return request.put<SessionResponse>(
      `/api/voice-interview/sessions/${sessionId}/resume`
    );
  },

  async getAllSessions(status?: string): Promise<SessionMeta[]> {
    const params = new URLSearchParams();
    if (status) params.append('status', status);

    return request.get<SessionMeta[]>(
      `/api/voice-interview/sessions${params.toString() ? `?${params.toString()}` : ''}`
    );
  },

  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/voice-interview/sessions/${sessionId}`);
  },
};

export class VoiceInterviewWebSocket {
  private ws: WebSocket | null = null;
  private url: string;
  private handlers: WebSocketEventHandlers;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 3;
  private reconnectDelay = 2000;

  constructor(_sessionId: number, url: string, handlers: WebSocketEventHandlers) {
    this.url = url;
    this.handlers = handlers;
  }

  connect(): void {
    try {
      this.ws = new WebSocket(this.url);

      this.ws.onopen = () => {
        this.reconnectAttempts = 0;
        this.handlers.onOpen?.();
      };

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WebSocketMessage;
          this.handlers.onMessage?.(message);

          switch (message.type) {
            case 'subtitle':
              this.handlers.onSubtitle?.(
                message.text,
                (message as WebSocketSubtitleMessage).isFinal
              );
              break;
            case 'audio':
              if ('text' in message) {
                const audioMsg = message as WebSocketAudioResponseMessage;
                this.handlers.onAudioResponse?.(audioMsg.data, audioMsg.text);
              }
              break;
            case 'audio_chunk':
              if ('index' in message) {
                const chunkMsg = message as WebSocketAudioChunkMessage;
                this.handlers.onAudioChunk?.(chunkMsg.data, chunkMsg.index, chunkMsg.isLast);
              }
              break;
            case 'text':
              if ('content' in message) {
                const textMsg = message as WebSocketTextMessage;
                this.handlers.onTextResponse?.(textMsg.content, !!textMsg.final);
              }
              break;
            case 'control':
              this.handlers.onControl?.(message.action, message.message);
              break;
            case 'error':
              this.handlers.onErrorMessage?.(message.message);
              break;
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };

      this.ws.onclose = (event) => {
        this.handlers.onClose?.(event);

        if (!event.wasClean && this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          setTimeout(() => this.connect(), this.reconnectDelay);
        }
      };

      this.ws.onerror = (error) => {
        this.handlers.onError?.(error);
      };
    } catch (error) {
      console.error('Error creating WebSocket connection:', error);
      this.handlers.onError?.(error as Event);
    }
  }

  sendAudio(audioData: string): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message: WebSocketAudioMessage = {
        type: 'audio',
        data: audioData,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  sendControl(action: string, data?: Record<string, unknown>): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message = {
        type: 'control',
        action,
        data,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  disconnect(): void {
    if (this.ws) {
      this.reconnectAttempts = this.maxReconnectAttempts;
      this.ws.close(1000, 'User disconnected');
      this.ws = null;
    }
  }

  getReadyState(): number {
    return this.ws?.readyState ?? WebSocket.CLOSED;
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }
}

export function connectWebSocket(
  sessionId: number,
  webSocketUrl: string,
  handlers: WebSocketEventHandlers
): VoiceInterviewWebSocket {
  const token = getStoredToken();
  const separator = webSocketUrl.includes('?') ? '&' : '?';
  const url = token
    ? `${webSocketUrl}${separator}token=${encodeURIComponent(token)}`
    : webSocketUrl;
  const ws = new VoiceInterviewWebSocket(sessionId, url, handlers);
  ws.connect();
  return ws;
}

export default voiceInterviewApi;
