/**
 * Вспомогательные функции интерфейса: экранирование, форматирование,
 * модальные окна и toast-уведомления (Plan.md, раздел 6).
 *
 * Все вставки пользовательских данных в HTML должны проходить через
 * {@link escapeHtml}, чтобы исключить XSS (Plan.md, раздел 8.1).
 */

/**
 * Экранировать значение для безопасной вставки в HTML.
 *
 * @param {unknown} value произвольное значение
 * @returns {string} экранированная строка
 */
export function escapeHtml(value) {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

const moneyFormatter = new Intl.NumberFormat('ru-RU', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const areaFormatter = new Intl.NumberFormat('ru-RU', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

const percentFormatter = new Intl.NumberFormat('ru-RU', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

const dateFormatter = new Intl.DateTimeFormat('ru-RU', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

const dateTimeFormatter = new Intl.DateTimeFormat('ru-RU', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/**
 * Форматировать денежную сумму.
 *
 * @param {number|string|null|undefined} value сумма
 * @returns {string} строка вида `12 345,00 ₽`
 */
export function formatMoney(value) {
  const number = toNumber(value);
  if (number === null) {
    return '—';
  }
  return `${moneyFormatter.format(number)} ₽`;
}

/**
 * Форматировать площадь.
 *
 * @param {number|string|null|undefined} value площадь в м²
 * @returns {string} строка вида `124,5 м²`
 */
export function formatArea(value) {
  const number = toNumber(value);
  if (number === null) {
    return '—';
  }
  return `${areaFormatter.format(number)} м²`;
}

/**
 * Форматировать процент.
 *
 * @param {number|string|null|undefined} value значение в процентах
 * @returns {string} строка вида `87,5 %`
 */
export function formatPercent(value) {
  const number = toNumber(value);
  if (number === null) {
    return '—';
  }
  return `${percentFormatter.format(number)} %`;
}

/**
 * Форматировать дату `YYYY-MM-DD`.
 *
 * @param {string|null|undefined} value дата в ISO-формате
 * @returns {string} строка `ДД.ММ.ГГГГ`
 */
export function formatDate(value) {
  if (!value) {
    return '—';
  }
  const date = new Date(`${String(value).slice(0, 10)}T00:00:00`);
  return Number.isNaN(date.getTime()) ? String(value) : dateFormatter.format(date);
}

/**
 * Форматировать дату и время ISO-строки.
 *
 * @param {string|null|undefined} value дата-время
 * @returns {string} строка `ДД.ММ.ГГГГ ЧЧ:ММ`
 */
export function formatDateTime(value) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : dateTimeFormatter.format(date);
}

/**
 * Сегодняшняя дата в формате `YYYY-MM-DD` (для input[type=date]).
 *
 * @returns {string} дата
 */
export function todayIso() {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Сдвинуть ISO-дату на указанное число дней.
 *
 * @param {string} isoDate исходная дата
 * @param {number} days количество дней (может быть отрицательным)
 * @returns {string} новая дата
 */
export function addDaysIso(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00`);
  date.setDate(date.getDate() + days);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function toNumber(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const number = Number(value);
  return Number.isNaN(number) ? null : number;
}

const STATUS_LABELS = {
  AVAILABLE: 'Свободно',
  RENTED: 'Арендовано',
  RESERVED: 'Зарезервировано',
  MAINTENANCE: 'На обслуживании',
  OFFICE: 'Офис',
  RETAIL: 'Торговое',
  CONFERENCE: 'Переговорная',
  PARKING: 'Паркинг',
  DRAFT: 'Черновик',
  ACTIVE: 'Активен',
  COMPLETED: 'Завершён',
  TERMINATED: 'Расторгнут',
  PENDING: 'Ожидает',
  PAID: 'Оплачен',
  OVERDUE: 'Просрочен',
  PARTIAL: 'Частично',
  OPEN: 'Открыта',
  IN_PROGRESS: 'В работе',
  RESOLVED: 'Решена',
  CANCELLED: 'Отменена',
  CRITICAL: 'Критический',
  HIGH: 'Высокий',
  MEDIUM: 'Средний',
  LOW: 'Низкий',
  ADMIN: 'Управляющий',
  MANAGER: 'Менеджер',
  ELECTRICAL: 'Электрика',
  PLUMBING: 'Сантехника',
  HVAC: 'Вентиляция',
  IT_NETWORK: 'Сеть',
  GENERAL: 'Общее',
};

/**
 * Человекочитаемая подпись статуса/типа.
 *
 * @param {string|null|undefined} code технический код
 * @returns {string} подпись на русском или исходный код
 */
export function labelOf(code) {
  if (!code) {
    return '—';
  }
  return STATUS_LABELS[code] || code;
}

const BADGE_VARIANTS = {
  AVAILABLE: 'success',
  FREE: 'success',
  ACTIVE: 'success',
  PAID: 'success',
  RESOLVED: 'success',
  RENTED: 'info',
  IN_PROGRESS: 'info',
  RESERVED: 'warning',
  PENDING: 'warning',
  PARTIAL: 'warning',
  DRAFT: 'neutral',
  COMPLETED: 'neutral',
  MAINTENANCE: 'danger',
  OVERDUE: 'danger',
  TERMINATED: 'danger',
  OPEN: 'info',
  CANCELLED: 'neutral',
  CRITICAL: 'danger',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'neutral',
};

/**
 * HTML статусного бейджа.
 *
 * @param {string|null|undefined} code технический код статуса
 * @returns {string} разметка `<span class="badge ...">`
 */
export function statusBadge(code) {
  const variant = BADGE_VARIANTS[code] || 'neutral';
  return `<span class="badge badge-${variant}">${escapeHtml(labelOf(code))}</span>`;
}

/* ------------------------------------------------------------
   Toast-уведомления
   ------------------------------------------------------------ */

/**
 * Показать toast-уведомление.
 *
 * @param {string} message текст
 * @param {'success'|'error'|'info'} [variant] тип уведомления
 */
export function toast(message, variant = 'info') {
  const root = document.getElementById('toast-root');
  if (!root) {
    return;
  }
  const node = document.createElement('div');
  node.className = `toast is-${variant}`;
  node.textContent = message;
  root.appendChild(node);
  window.setTimeout(() => {
    node.remove();
  }, variant === 'error' ? 6000 : 3500);
}

/* ------------------------------------------------------------
   Модальные окна
   ------------------------------------------------------------ */

let modalCloser = null;

/**
 * Открыть модальное окно.
 *
 * @param {{title: string, bodyHtml?: string, footerHtml?: string, onMount?: (body: HTMLElement) => void}} options параметры окна
 */
export function openModal({ title, bodyHtml = '', footerHtml = '', onMount }) {
  const backdrop = document.getElementById('modal-root');
  const titleEl = document.getElementById('modal-title');
  const body = document.getElementById('modal-body');
  const footer = document.getElementById('modal-footer');

  titleEl.textContent = title;
  body.innerHTML = bodyHtml;
  footer.innerHTML = footerHtml;
  backdrop.hidden = false;
  document.body.style.overflow = 'hidden';

  if (typeof onMount === 'function') {
    onMount(body);
  }

  const focusable = backdrop.querySelector(
    'input, select, textarea, button:not(#modal-close), [tabindex]:not([tabindex="-1"])',
  );
  if (focusable) {
    focusable.focus();
  } else {
    document.getElementById('modal-close')?.focus();
  }
}

/** Закрыть модальное окно. */
export function closeModal() {
  const backdrop = document.getElementById('modal-root');
  backdrop.hidden = true;
  document.getElementById('modal-body').innerHTML = '';
  document.getElementById('modal-footer').innerHTML = '';
  document.body.style.overflow = '';
}

/** Инициализировать обработчики закрытия окна (Esc, клик по фону, кнопка). */
export function initModal() {
  const backdrop = document.getElementById('modal-root');
  const close = () => closeModal();

  document.getElementById('modal-close')?.addEventListener('click', close);
  backdrop?.addEventListener('mousedown', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !backdrop.hidden) {
      close();
    }
  });

  modalCloser = close;
}

/** Закрыть окно, если оно открыто (для навигации между экранами). */
export function closeModalIfOpen() {
  const backdrop = document.getElementById('modal-root');
  if (backdrop && !backdrop.hidden && modalCloser) {
    modalCloser();
  }
}

/* ------------------------------------------------------------
   Состояния загрузки и пустые списки
   ------------------------------------------------------------ */

/**
 * Разметка индикатора загрузки.
 *
 * @param {string} [text] подпись
 * @returns {string} HTML
 */
export function loadingHtml(text = 'Загрузка данных…') {
  return `<div class="loading-state"><span class="spinner" aria-hidden="true"></span><p>${escapeHtml(text)}</p></div>`;
}

/**
 * Разметка пустого состояния.
 *
 * @param {string} title заголовок
 * @param {string} [text] пояснение
 * @returns {string} HTML
 */
export function emptyHtml(title, text = '') {
  return `<div class="empty-state"><h3>${escapeHtml(title)}</h3>${text ? `<p>${escapeHtml(text)}</p>` : ''}</div>`;
}

/**
 * Извлечь сообщения об ошибках из `details` ответа API.
 *
 * @param {unknown} error ошибка
 * @returns {string[]} список сообщений
 */
export function errorDetails(error) {
  if (error && Array.isArray(error.payload?.details)) {
    return error.payload.details;
  }
  return [];
}
