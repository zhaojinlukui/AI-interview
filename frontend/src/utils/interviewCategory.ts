const LEGACY_FOLLOW_UP_CATEGORY_PATTERN = /^(.*?)\s*follow[-_\s]*up\s*(\d+)\s*$/i;

export function formatInterviewCategory(category?: string | null): string {
  if (!category || !category.trim()) {
    return '';
  }

  const trimmed = category.trim();
  const match = trimmed.match(LEGACY_FOLLOW_UP_CATEGORY_PATTERN);
  if (!match) {
    return trimmed;
  }

  const baseCategory = match[1].trim();
  const order = match[2];
  return baseCategory ? `${baseCategory} 追问${order}` : `追问${order}`;
}
