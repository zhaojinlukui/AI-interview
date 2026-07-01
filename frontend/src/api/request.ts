import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

/**
 * 后端统一响应结构
 */
interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

const UPLOAD_SIZE_ERROR_MESSAGE = '文件大小超过限制，请确认单个文件不超过 100MB，单次导入总大小不超过 500MB';

const baseURL = import.meta.env.VITE_API_BASE_URL || '';
export const AUTH_TOKEN_STORAGE_KEY = 'ai-interview-auth-token';
export const AUTH_EXPIRED_EVENT = 'auth:expired';

export function getStoredToken(): string | null {
  return localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
}

export function setStoredToken(token: string): void {
  localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
}

export function clearStoredToken(): void {
  localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
}

export function buildAuthHeaders(): Record<string, string> {
  const token = getStoredToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function notifyAuthExpired() {
  clearStoredToken();
  window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
}

export function resolveApiUrl(path: string): string {
  if (!path.startsWith('/')) {
    return path;
  }
  return `${baseURL}${path}`;
}

const instance: AxiosInstance = axios.create({
  baseURL,
  timeout: 60000,
});

instance.interceptors.request.use((config) => {
  const token = getStoredToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * 响应拦截器
 * 
 * 后端约定：所有响应都是 HTTP 200 + Result
 * - code === 200 → 成功，返回 data
 * - code !== 200 → 失败，直接显示 message
 */
instance.interceptors.response.use(
  (response) => {
    const result = response.data as Result;
    
    // 检查是否是 Result 格式
    if (result && typeof result === 'object' && 'code' in result) {
      if (result.code === 200) {
        // 成功：返回 data
        response.data = result.data;
        return response;
      }
      if (result.code === 401 || result.code === 12005 || result.code === 12006) {
        notifyAuthExpired();
      }
      // 失败：直接抛出 message
      return Promise.reject(new Error(result.message || '请求失败'));
    }
    
    // 非 Result 格式，直接返回
    return response;
  },
  (error) => {
    // 有响应的情况：后端返回了结果（即使是错误）
    if (error.response) {
      const { data, status } = error.response;
      // 尝试解析 Result 格式
      if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
        const result = data as Result;
        if (result.code === 401 || result.code === 12005 || result.code === 12006) {
          notifyAuthExpired();
        }
        return Promise.reject(new Error(result.message || '请求失败'));
      }
      if (status === 401) {
        notifyAuthExpired();
      }
      if (status === 413) {
        return Promise.reject(new Error(UPLOAD_SIZE_ERROR_MESSAGE));
      }
      if (typeof data === 'string' && data.includes('Maximum upload size exceeded')) {
        return Promise.reject(new Error(UPLOAD_SIZE_ERROR_MESSAGE));
      }
      // 响应格式不对
      return Promise.reject(new Error(`请求失败（HTTP ${status}），请重试`));
    }

    // 没有响应的情况：真正的网络错误或连接被重置
    // 对于文件上传，可能是网络超时或连接中断，但不一定是文件大小问题
    // 让后端返回真实的错误信息，而不是在这里假设
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/upload') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      // 文件上传失败且没有响应，可能是网络超时或连接中断
      // 不直接假设是文件大小问题，返回更通用的错误信息
      return Promise.reject(new Error('上传失败，可能是网络超时或连接中断，请重试'));
    }

    // 其他网络错误
    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: 300000, // 5分钟，与Nginx proxy_read_timeout对齐
      headers: { 'Content-Type': 'multipart/form-data' },
      ...config,
    }).then(res => res.data);
  },

  /**
   * 获取原始实例（用于特殊场景如下载 Blob）
   */
  getInstance(): AxiosInstance {
    return instance;
  },
};

/**
 * 获取错误信息
 */
export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}

export default request;
