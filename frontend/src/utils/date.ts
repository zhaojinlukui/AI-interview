/**
 * 日期格式化工具函数
 */

/**
 * 格式化日期为中文格式
 * @param dateStr 日期字符串
 * @param options 格式化选项
 * @returns 格式化后的日期字符串
 */
export function formatDate(
  dateStr: string | null | undefined,
  options?: {
    year?: 'numeric' | '2-digit';
    month?: 'numeric' | '2-digit';
    day?: 'numeric' | '2-digit';
    hour?: '2-digit';
    minute?: '2-digit';
  }
): string {
  if (!dateStr) return '-';
  
  const date = new Date(dateStr);
  if (isNaN(date.getTime())) return '-';
  
  const defaultOptions: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    ...options
  };
  
  return date.toLocaleDateString('zh-CN', defaultOptions);
}

/**
 * 格式化日期时间（包含时分）
 */
export function formatDateTime(dateStr: string | null | undefined): string {
  return formatDate(dateStr, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function padDatePart(value: number): string {
  return value.toString().padStart(2, '0');
}

/**
 * 格式化日期时间（包含时分秒）
 */
export function formatDateTimeWithSeconds(dateStr: string | null | undefined): string {
  if (!dateStr) return '-';

  const date = new Date(dateStr);
  if (isNaN(date.getTime())) return '-';

  const year = date.getFullYear();
  const month = padDatePart(date.getMonth() + 1);
  const day = padDatePart(date.getDate());
  const hour = padDatePart(date.getHours());
  const minute = padDatePart(date.getMinutes());
  const second = padDatePart(date.getSeconds());

  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

/**
 * 格式化日期（仅日期部分）
 */
export function formatDateOnly(dateStr: string | null | undefined): string {
  return formatDate(dateStr, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
}
