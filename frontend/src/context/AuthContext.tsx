import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi } from '../api/auth';
import {
  AUTH_EXPIRED_EVENT,
  clearStoredToken,
  getStoredToken,
  setStoredToken,
} from '../api/request';
import type { AuthUser, LoginRequest, RegisterRequest } from '../types/auth';

interface AuthContextValue {
  token: string | null;
  user: AuthUser | null;
  loading: boolean;
  isAdmin: boolean;
  login: (data: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(() => getStoredToken());
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    clearStoredToken();
    setToken(null);
    setUser(null);
  }, []);

  const refreshMe = useCallback(async () => {
    if (!getStoredToken()) {
      setLoading(false);
      return;
    }
    try {
      const currentUser = await authApi.me();
      setUser(currentUser);
    } catch {
      logout();
    } finally {
      setLoading(false);
    }
  }, [logout]);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  useEffect(() => {
    const handleExpired = () => logout();
    window.addEventListener(AUTH_EXPIRED_EVENT, handleExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handleExpired);
  }, [logout]);

  const applyAuthResponse = useCallback((accessToken: string, currentUser: AuthUser) => {
    setStoredToken(accessToken);
    setToken(accessToken);
    setUser(currentUser);
  }, []);

  const login = useCallback(async (data: LoginRequest) => {
    const response = await authApi.login(data);
    applyAuthResponse(response.accessToken, response.user);
  }, [applyAuthResponse]);

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await authApi.register(data);
    applyAuthResponse(response.accessToken, response.user);
  }, [applyAuthResponse]);

  const value = useMemo<AuthContextValue>(() => ({
    token,
    user,
    loading,
    isAdmin: user?.role === 'ADMIN',
    login,
    register,
    logout,
    refreshMe,
  }), [loading, login, logout, refreshMe, register, token, user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
