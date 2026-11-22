/**
 * Вспомогательные функции интерфейса: экранирование, форматирование,
 * модальные окна, toast-уведомления и SVG-иконки (Plan.md, раздел 6).
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
 * Добавлена визуальная точка-индикатор для обеспечения доступности по WCAG 1.4.1 (цвет + форма).
 *
 * @param {string|null|undefined} code технический код статуса
 * @returns {string} разметка `<span class="badge ...">`
 */
export function statusBadge(code) {
  const variant = BADGE_VARIANTS[code] || 'neutral';
  return `<span class="badge badge-${variant}"><span class="badge-dot" aria-hidden="true"></span>${escapeHtml(labelOf(code))}</span>`;
}

/* ------------------------------------------------------------
   SVG-иконки (better-ui / single SVG recolored per state)
   ------------------------------------------------------------ */

const SVG_ICONS = {
  copy: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>`,
  check: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`,
  external: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>`,
  edit: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg>`,
  plus: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>`,
  building: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21h18"/><path d="M6 21V7a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v14"/><path d="M9 10h1"/><path d="M9 14h1"/><path d="M14 10h1"/><path d="M14 14h1"/></svg>`,
  phone: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
  mail: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>`,
  calendar: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>`,
  alert: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
  search: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>`,
  close: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
};

/**
 * Отрендерить SVG-иконку по имени.
 *
 * @param {string} name имя иконки
 * @param {string} [className] CSS-класс
 * @returns {string} разметка SVG
 */
export function renderIcon(name, className = 'ui-icon') {
  const svg = SVG_ICONS[name];
  if (!svg) {
    return '';
  }
  return `<span class="${escapeHtml(className)}" aria-hidden="true">${svg}</span>`;
}

/**
 * Скопировать текст в буфер обмена с всплывающим уведомлением.
 *
 * @param {string} text текст для копирования
 * @param {string} [successMessage] текст уведомления
 */
export async function copyText(text, successMessage = 'Скопировано в буфер обмена') {
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }
    toast(successMessage, 'success');
  } catch {
    toast('Не удалось скопировать в буфер', 'error');
  }
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
  node.setAttribute('role', variant === 'error' ? 'alert' : 'status');

  const iconName = variant === 'success' ? 'check' : variant === 'error' ? 'alert' : 'building';
  node.innerHTML = `
    <span class="toast-icon" aria-hidden="true">${renderIcon(iconName)}</span>
    <span class="toast-message">${escapeHtml(message)}</span>
  `;

  root.appendChild(node);
  window.setTimeout(() => {
    node.style.opacity = '0';
    node.style.transform = 'translateY(-6px)';
    node.style.transition = 'opacity 180ms ease, transform 180ms ease';
    window.setTimeout(() => node.remove(), 200);
  }, variant === 'error' ? 6000 : 3500);
}

/* ------------------------------------------------------------
   Модальные окна (better-accessibility: focus trap + restore)
   ------------------------------------------------------------ */

let modalCloser = null;
let lastFocusedElement = null;

/**
 * Открыть модальное окно.
 *
 * @param {{
 *   title: string,
 *   bodyHtml?: string,
 *   footerHtml?: string,
 *   size?: 'sm'|'md'|'lg'|'xl',
 *   onMount?: (body: HTMLElement) => void
 * }} options параметры окна
 */
export function openModal({ title, bodyHtml = '', footerHtml = '', size = 'md', onMount }) {
  lastFocusedElement = document.activeElement;

  const backdrop = document.getElementById('modal-root');
  const container = document.getElementById('modal-container') || backdrop.querySelector('.modal');
  const titleEl = document.getElementById('modal-title');
  const body = document.getElementById('modal-body');
  const footer = document.getElementById('modal-footer');

  // Установка размера модального окна (sm, md, lg, xl)
  if (container) {
    container.classList.remove('modal-sm', 'modal-md', 'modal-lg', 'modal-xl');
    container.classList.add(`modal-${size}`);
  }

  titleEl.innerHTML = title;
  body.innerHTML = bodyHtml;
  footer.innerHTML = footerHtml;
  backdrop.hidden = false;
  document.body.classList.add('modal-open');

  if (typeof onMount === 'function') {
    onMount(body);
  }

  // Фокус на первый интерактивный элемент внутри модального окна
  const focusable = backdrop.querySelector(
    'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not(#modal-close):not([disabled]), [tabindex]:not([tabindex="-1"])',
  );
  if (focusable) {
    focusable.focus();
  } else {
    document.getElementById('modal-close')?.focus();
  }
}

/** Закрыть модальное окно и вернуть фокус. */
export function closeModal() {
  const backdrop = document.getElementById('modal-root');
  if (!backdrop || backdrop.hidden) {
    return;
  }
  backdrop.hidden = true;
  document.getElementById('modal-body').innerHTML = '';
  document.getElementById('modal-footer').innerHTML = '';
  document.body.classList.remove('modal-open');

  // Возврат фокуса на элемент, вызвавший модальное окно
  if (lastFocusedElement && typeof lastFocusedElement.focus === 'function') {
    try {
      lastFocusedElement.focus();
    } catch {
      // Игнорируем, если элемент был удалён из DOM
    }
    lastFocusedElement = null;
  }
}

/** Инициализировать обработчики закрытия окна (Esc, клик по фону, кнопка, ловушка Tab). */
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
    if (backdrop.hidden) {
      return;
    }

    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }

    // Фокус-ловушка (Tab / Shift+Tab)
    if (event.key === 'Tab') {
      const focusables = Array.from(
        backdrop.querySelectorAll(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.offsetParent !== null);

      if (!focusables.length) {
        return;
      }

      const first = focusables[0];
      const last = focusables[focusables.length - 1];

      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
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
  return `<div class="loading-state" role="status"><span class="spinner" aria-hidden="true"></span><p>${escapeHtml(text)}</p></div>`;
}

/**
 * Разметка пустого состояния с опциональной кнопкой действия.
 *
 * @param {string} title заголовок
 * @param {string} [text] пояснение
 * @param {string} [actionHtml] опциональная кнопка действия
 * @returns {string} HTML
 */
export function emptyHtml(title, text = '', actionHtml = '') {
  return `
    <div class="empty-state">
      <div class="empty-state-icon" aria-hidden="true">
        ${renderIcon('alert')}
      </div>
      <h3>${escapeHtml(title)}</h3>
      ${text ? `<p class="empty-state-text">${escapeHtml(text)}</p>` : ''}
      ${actionHtml ? `<div class="empty-state-actions">${actionHtml}</div>` : ''}
    </div>
  `;
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
