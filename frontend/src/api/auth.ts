import { request } from './request';
import type {
  AdminUser,
  AuthResponse,
  AuthUser,
  LoginRequest,
  ProfileStats,
  RegisterRequest,
} from '../types/auth';

export const authApi = {
  register(data: RegisterRequest): Promise<AuthResponse> {
    return request.post<AuthResponse>('/api/auth/register', data);
  },

  login(data: LoginRequest): Promise<AuthResponse> {
    return request.post<AuthResponse>('/api/auth/login', data);
  },

  me(): Promise<AuthUser> {
    return request.get<AuthUser>('/api/auth/me');
  },
};

export const profileApi = {
  getStats(): Promise<ProfileStats> {
    return request.get<ProfileStats>('/api/profile/stats');
  },

  updateDisplayName(displayName: string): Promise<AuthUser> {
    return request.put<AuthUser>('/api/profile/display-name', { displayName });
  },

  updatePassword(currentPassword: string, newPassword: string): Promise<void> {
    return request.put<void>('/api/profile/password', { currentPassword, newPassword });
  },
};

export const adminUserApi = {
  listUsers(): Promise<AdminUser[]> {
    return request.get<AdminUser[]>('/api/admin/users');
  },

  updatePassword(id: number, newPassword: string): Promise<void> {
    return request.put<void>(`/api/admin/users/${id}/password`, { newPassword });
  },
};
