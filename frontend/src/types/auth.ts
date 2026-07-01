export type UserRole = 'USER' | 'ADMIN';

export interface AuthUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  lastLoginAt?: string | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresAtEpochSecond: number;
  user: AuthUser;
}

export interface RegisterRequest {
  username: string;
  password: string;
  displayName?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface PeriodMetric {
  days: number;
  interviewCount: number;
  previousInterviewCount: number;
  interviewCountChange: number;
  averageScore: number | null;
  previousAverageScore: number | null;
  averageScoreChange: number | null;
}

export interface GrowthTrendPoint {
  date: string;
  interviewCount: number;
  averageScore: number | null;
}

export interface WeaknessTrend {
  item: string;
  averageScore: number | null;
  previousAverageScore: number | null;
  averageScoreChange: number | null;
  sampleCount: number;
}

export interface ProfileStats {
  last7Days: PeriodMetric;
  last30Days: PeriodMetric;
  growthTrend: GrowthTrendPoint[];
  weakItems: WeaknessTrend[];
}

export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  updatedAt?: string | null;
  lastLoginAt?: string | null;
}
