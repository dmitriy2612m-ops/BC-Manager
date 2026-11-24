/**
 * Обёртка над fetch для REST API BC-Manager (Plan.md, разделы 5, 6).
 *
 * Модуль отвечает за:
 *  - автоматическую подстановку JWT из localStorage;
 *  - разбор единого формата ошибок API (`{timestamp,status,error,message,details}`);
 *  - обработку 401 (сброс сессии и возврат на экран входа);
 *  - скачивание CSV-файлов с учётом заголовка `Content-Disposition`.
 */

const BASE_URL = '/api/v1';
const TOKEN_KEY = 'bcmanager.token';
const USER_KEY = 'bcmanager.user';

/** Заголовок, который API возвращает вместе с сообщением об ошибке. */
export class ApiError extends Error {
  /**
   * @param {number} status HTTP-статус
   * @param {string} message сообщение для пользователя
   * @param {unknown} payload тело ответа
   */
  constructor(status, message, payload) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
  }
}

/**
 * Сохранить сессию после успешного входа.
 *
 * @param {{token: string, username: string, fullName: string, role: string}} data ответ `/auth/login`
 */
export function saveSession(data) {
  localStorage.setItem(TOKEN_KEY, data.token);
  localStorage.setItem(USER_KEY, JSON.stringify({
    username: data.username,
    fullName: data.fullName,
    role: data.role,
  }));
}

/** Прочитать сохранённого пользователя или `null`. */
export function getCurrentUser() {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/** Токен из localStorage или пустая строка. */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

/** Признак наличия сохранённой сессии. */
export function isAuthenticated() {
  return Boolean(getToken());
}

/** Очистить сессию (выход из системы). */
export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/** Сообщить приложению, что сессия истекла (обрабатывается в app.js). */
function notifyUnauthorized() {
  clearSession();
  window.dispatchEvent(new CustomEvent('bc:unauthorized'));
}

/**
 * Базовый запрос к API.
 *
 * @param {string} path путь относительно `/api/v1`
 * @param {RequestInit & {body?: unknown}} [options] настройки запроса; объект `body` сериализуется в JSON
 * @returns {Promise<unknown>} распарсенный JSON или `null` для ответов без тела
 */
async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const token = getToken();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  let body = options.body;
  if (body !== undefined && body !== null && !(body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(body);
  } else if (body === null) {
    body = undefined;
  }

  let response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...options, headers, body });
  } catch {
    throw new ApiError(0, 'Сервер недоступен. Проверьте подключение и повторите попытку.');
  }

  if (response.status === 401) {
    const isAuthAttempt = path === '/auth/login' || path === '/auth/register';
    if (!isAuthAttempt) {
      notifyUnauthorized();
      throw new ApiError(401, 'Сессия истекла. Войдите в систему заново.');
    }
    throw await toApiError(response);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers.get('Content-Type') || '';
  if (contentType.includes('application/json')) {
    return response.json();
  }
  return response.text();
}

/**
 * Преобразовать неуспешный ответ в {@link ApiError}.
 *
 * @param {Response} response ответ fetch
 * @returns {Promise<ApiError>} ошибка с сообщением из единого формата
 */
async function toApiError(response) {
  let payload = null;
  let message = `Ошибка запроса (${response.status})`;
  try {
    const text = await response.text();
    if (text) {
      payload = JSON.parse(text);
      if (payload && typeof payload.message === 'string' && payload.message.trim()) {
        message = payload.message;
      }
      if (payload && Array.isArray(payload.details) && payload.details.length) {
        const formatted = payload.details
          .map((item) => (typeof item === 'string' ? item : item?.message || item?.field || ''))
          .filter(Boolean);
        if (formatted.length) {
          message = `${message}: ${formatted.join('; ')}`;
        }
      }
    }
  } catch {
    // Тело не JSON — оставляем общее сообщение.
  }
  return new ApiError(response.status, message, payload);
}

/**
 * Скачать файл по защищённому эндпоинту (например, CSV-экспорт).
 *
 * @param {string} path путь относительно `/api/v1`
 * @param {string} fallbackName имя файла, если сервер не прислал `Content-Disposition`
 */
export async function downloadFile(path, fallbackName) {
  const headers = new Headers();
  const token = getToken();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(`${BASE_URL}${path}`, { headers });
  if (response.status === 401) {
    const isAuthAttempt = path === '/auth/login' || path === '/auth/register';
    if (!isAuthAttempt) {
      notifyUnauthorized();
      throw new ApiError(401, 'Сессия истекла. Войдите в систему заново.');
    }
    throw await toApiError(response);
  }
  if (!response.ok) {
    throw await toApiError(response);
  }

  const disposition = response.headers.get('Content-Disposition') || '';
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  const fileName = match ? match[1] : fallbackName;

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Эндпоинты API, сгруппированные по разделам спецификации. */
export const api = {
  login: (username, password) =>
    request('/auth/login', { method: 'POST', body: { username, password } }),
  register: (username, password) =>
    request('/auth/register', { method: 'POST', body: { username, password } }),
  me: () => request('/auth/me'),

  premises: {
    list: (filters = {}) => request(`/premises${toQuery(filters)}`),
    get: (id) => request(`/premises/${id}`),
    create: (payload) => request('/premises', { method: 'POST', body: payload }),
    update: (id, payload) => request(`/premises/${id}`, { method: 'PUT', body: payload }),
    remove: (id) => request(`/premises/${id}`, { method: 'DELETE' }),
    match: (criteria) => request('/premises/match', { method: 'POST', body: criteria }),
  },

  tenants: {
    list: (filters = {}) => request(`/tenants${toQuery(filters)}`),
    get: (id) => request(`/tenants/${id}`),
    create: (payload) => request('/tenants', { method: 'POST', body: payload }),
    update: (id, payload) => request(`/tenants/${id}`, { method: 'PUT', body: payload }),
    deactivate: (id) => request(`/tenants/${id}/deactivate`, { method: 'PATCH' }),
  },

  contracts: {
    list: (filters = {}) => request(`/contracts${toQuery(filters)}`),
    get: (id) => request(`/contracts/${id}`),
    preview: (payload) => request('/contracts/preview', { method: 'POST', body: payload }),
    create: (payload) => request('/contracts', { method: 'POST', body: payload }),
    changeStatus: (id, status) =>
      request(`/contracts/${id}/status`, { method: 'PATCH', body: { status } }),
  },

  payments: {
    list: (filters = {}) => request(`/payments${toQuery(filters)}`),
    pay: (id, payload) => request(`/payments/${id}/pay`, { method: 'POST', body: payload }),
    recalculate: (payload) =>
      request('/payments/recalculate-penalties', { method: 'POST', body: payload }),
  },

  maintenance: {
    list: (filters = {}) => request(`/maintenance${toQuery(filters)}`),
    create: (payload) => request('/maintenance', { method: 'POST', body: payload }),
    changeStatus: (id, status) =>
      request(`/maintenance/${id}/status`, { method: 'PATCH', body: { status } }),
  },

  analytics: {
    summary: (asOf) => request(`/analytics/summary${toQuery({ asOf })}`),
    exportContractsCsv: () => downloadFile('/analytics/export/csv', 'contracts.csv'),
    exportPremisesCsv: () => downloadFile('/analytics/export/premises/csv', 'premises.csv'),
  },
};

/**
 * Собрать query-строку, пропуская пустые значения.
 *
 * @param {Record<string, unknown>} params параметры фильтра
 * @returns {string} строка вида `?a=1&b=2` или пустая строка
 */
function toQuery(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}
