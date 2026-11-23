export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
export async function request<T>(path: string, method = 'GET', body?: unknown, key?: string): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    const tokenResponse = await fetch('/api/v1/csrf');
    if (!tokenResponse.ok) throw new ApiError(tokenResponse.status, 'CSRF_UNAVAILABLE', '无法取得操作凭证，输入仍保留。');
    const csrf = await tokenResponse.json();
    headers[csrf.header_name] = csrf.token;
    if (key) headers['Idempotency-Key'] = key;
    headers['Content-Type'] = body instanceof URLSearchParams ? 'application/x-www-form-urlencoded' : 'application/json';
  }
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : body instanceof URLSearchParams ? body : JSON.stringify(body) });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(response.status, error.code ?? 'HTTP_ERROR', error.message ?? '操作失败，请保留输入。');
  }
  return response.status === 204 ? undefined as T : response.json();
}