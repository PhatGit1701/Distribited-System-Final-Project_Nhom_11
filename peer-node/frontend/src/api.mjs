export class ApiError extends Error {
  constructor(message, status, code = 'REQUEST_FAILED') {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export async function request(path, { method = 'GET', body, token, signal } = {}) {
  const headers = { Accept: 'application/json' };
  const multipart = body instanceof FormData;
  if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  if (token && method !== 'GET') headers['X-Node-Token'] = token;
  const response = await fetch(`/api/node${path}`, {
    method, headers, credentials: 'same-origin', signal,
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
  const text = await response.text();
  let value;
  try { value = text ? JSON.parse(text) : null; } catch { value = null; }
  if (!response.ok) {
    throw new ApiError(value?.message || value?.error || `Request failed (${response.status})`, response.status, value?.code);
  }
  return value;
}
