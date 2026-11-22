/**
 * Заявки на техобслуживание (Plan.md, раздел 6.1, п. 7).
 *
 * Приоритетная очередь с индикацией SLA-эскалации (Алгоритм 5), создание
 * заявки и быстрая смена статусов.
 *
 * Контракт API (раздел 5.6):
 *  - `GET   /maintenance?prioritized=true|false` — очередь/реестр;
 *  - `POST  /maintenance`                        — создание;
 *  - `PATCH /maintenance/{id}/status`            — смена статуса.
 */

import { api } from '../api.js';
import {
  emptyHtml,
  escapeHtml,
  formatDate,
  formatDateTime,
  labelOf,
  loadingHtml,
  openModal,
  closeModal,
  statusBadge,
  toast,
} from '../ui.js';

const CATEGORY_OPTIONS = [
  ['ELECTRICAL', 'Электрика'],
  ['PLUMBING', 'Сантехника'],
  ['HVAC', 'Вентиляция'],
  ['IT_NETWORK', 'Сеть'],
  ['GENERAL', 'Общее'],
];

const PRIORITY_OPTIONS = [
  ['CRITICAL', 'Критический'],
  ['HIGH', 'Высокий'],
  ['MEDIUM', 'Средний'],
  ['LOW', 'Низкий'],
];

/** Веса приоритета для подсветки (1 — самый срочный). */
const WEIGHT_LABELS = { 1: 'Критический', 2: 'Высокий', 3: 'Средний', 4: 'Низкий' };

/** Состояние экрана. */
const state = {
  prioritized: true,
  rows: [],
  premises: null,
  tenants: null,
};

/**
 * Отрисовать экран заявок на ТО.
 *
 * @param {HTMLElement} root контейнер контента
 * @param {{navigate: Function, context?: object}} options окружение
 */
export async function renderMaintenance(root) {
  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Заявки на ТО</h1>
        <p class="page-subtitle">Приоритетная очередь с учётом SLA: 24 ч для HIGH, 48 ч для MEDIUM</p>
        <div class="page-header-actions">
          <button class="btn btn-primary" type="button" id="add-request">Новая заявка</button>
          <button class="btn btn-secondary" type="button" id="toggle-view">
            ${state.prioritized ? 'Показать реестр' : 'Показать очередь'}
          </button>
          <button class="btn btn-ghost" type="button" id="reload-maintenance">Обновить</button>
        </div>
      </header>

      <div id="maintenance-body">${loadingHtml()}</div>
    </div>
  `;

  root.querySelector('#reload-maintenance')?.addEventListener('click', () => loadTable(root));

  root.querySelector('#toggle-view')?.addEventListener('click', (event) => {
    state.prioritized = !state.prioritized;
    event.currentTarget.textContent = state.prioritized ? 'Показать реестр' : 'Показать очередь';
    loadTable(root);
  });

  root.querySelector('#add-request')?.addEventListener('click', () => {
    openCreateModal(root);
  });

  await loadTable(root);
}

/**
 * Загрузить и отрисовать заявки.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function loadTable(root) {
  const container = root.querySelector('#maintenance-body');
  if (!container) {
    return;
  }
  container.innerHTML = loadingHtml();

  let rows;
  try {
    rows = await api.maintenance.list({ prioritized: state.prioritized });
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    container.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить заявки</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  state.rows = Array.isArray(rows) ? rows : [];

  if (!state.rows.length) {
    container.innerHTML = emptyHtml(
      'Заявок нет',
      'Создайте заявку, чтобы она попала в приоритетную очередь.',
    );
    return;
  }

  const open = state.rows.filter((row) => isOpen(row));
  const escalated = open.filter((row) => row.escalated);

  container.innerHTML = `
    <div class="card summary-strip">
      <span>Открытых заявок: <strong>${open.length}</strong></span>
      <span>С эскалацией SLA: <strong>${escalated.length}</strong></span>
      <span>Критических: <strong>${open.filter((row) => Number(row.effectivePriority) === 1).length}</strong></span>
      <span>Режим: <strong>${state.prioritized ? 'приоритетная очередь' : 'реестр'}</strong></span>
    </div>

    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th class="num">#</th>
            <th>Тема</th>
            <th>Помещение</th>
            <th>Арендатор</th>
            <th>Категория</th>
            <th>Приоритет</th>
            <th>Поступила</th>
            <th>Статус</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          ${state.rows
            .map((row) => {
              const escalated = row.escalated;
              const cls = !isOpen(row)
                ? 'row-muted'
                : Number(row.effectivePriority) === 1
                  ? 'row-critical'
                  : '';
              return `
            <tr class="${cls}">
              <td class="num">${escapeHtml(row.queuePosition || '—')}</td>
              <td>
                <strong>${escapeHtml(row.title)}</strong>
                <span class="field-hint block-ellipsis">${escapeHtml(truncate(row.description, 80))}</span>
              </td>
              <td>${escapeHtml(row.premisesCode)}</td>
              <td>${escapeHtml(row.tenantName || '—')}</td>
              <td>${escapeHtml(labelOf(row.category))}</td>
              <td>
                ${statusBadge(row.priority)}
                ${
                  escalated
                    ? `<span class="badge badge-warning sla-badge" title="SLA-эскалация">SLA → ${
                        labelOf(WEIGHT_LABELS[Number(row.effectivePriority)])
                      }</span>`
                    : ''
                }
              </td>
              <td>${formatDateTime(row.createdAt)}</td>
              <td>${statusBadge(row.status)}</td>
              <td class="actions-cell">${rowActions(row)}</td>
            </tr>`;
            })
            .join('')}
        </tbody>
      </table>
    </div>
  `;

  bindRowActions(root, container);
}

/**
 * Открыта ли заявка (участвует в очереди).
 *
 * @param {object} row заявка
 * @returns {boolean} признак
 */
function isOpen(row) {
  return row.status === 'OPEN' || row.status === 'IN_PROGRESS';
}

/**
 * Обрезать текст с многоточием.
 *
 * @param {string} text исходный текст
 * @param {number} limit максимальная длина
 * @returns {string} результат
 */
function truncate(text, limit) {
  const value = String(text || '');
  return value.length > limit ? `${value.slice(0, limit - 1).trimEnd()}…` : value;
}

/**
 * Кнопки действий для строки заявки.
 *
 * @param {object} row заявка
 * @returns {string} HTML кнопок
 */
function rowActions(row) {
  if (row.status === 'OPEN') {
    return `
      <button class="btn btn-secondary btn-sm" type="button" data-action="IN_PROGRESS" data-id="${row.id}">В работу</button>
      <button class="btn btn-ghost btn-sm" type="button" data-action="RESOLVED" data-id="${row.id}">Решено</button>`;
  }
  if (row.status === 'IN_PROGRESS') {
    return `
      <button class="btn btn-primary btn-sm" type="button" data-action="RESOLVED" data-id="${row.id}">Решено</button>
      <button class="btn btn-ghost btn-sm" type="button" data-action="CANCELLED" data-id="${row.id}">Отменить</button>`;
  }
  return '';
}

/**
 * Навесить обработчики смены статуса.
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {HTMLElement} container контейнер таблицы
 */
function bindRowActions(root, container) {
  container.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      const status = button.dataset.action;
      button.disabled = true;
      try {
        await api.maintenance.changeStatus(Number(button.dataset.id), status);
        toast(`Заявка переведена в статус «${labelOf(status)}»`, 'success');
        await loadTable(root);
      } catch (error) {
        toast(error?.message || 'Не удалось изменить статус заявки', 'error');
        button.disabled = false;
      }
    });
  });
}

/**
 * Загрузить справочники для формы создания заявки.
 *
 * @returns {Promise<{premises: object[], tenants: object[]}>} справочники
 */
async function loadDictionaries() {
  if (state.premises && state.tenants) {
    return { premises: state.premises, tenants: state.tenants };
  }
  const [premises, tenants] = await Promise.all([api.premises.list(), api.tenants.list()]);
  state.premises = Array.isArray(premises) ? premises : [];
  state.tenants = Array.isArray(tenants) ? tenants : [];
  return { premises: state.premises, tenants: state.tenants };
}

/**
 * Модальное окно создания заявки.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function openCreateModal(root) {
  let premises;
  let tenants;
  try {
    ({ premises, tenants } = await loadDictionaries());
  } catch (error) {
    toast(error?.message || 'Не удалось загрузить справочники', 'error');
    return;
  }

  openModal({
    size: 'md',
    title: 'Новая заявка на ТО',
    bodyHtml: `
      <form id="request-form" class="form-grid" novalidate>
        <label class="field">
          <span class="field-label">Помещение *</span>
          <select name="premisesId" required>
            <option value="">— выберите —</option>
            ${premises
              .map(
                (row) =>
                  `<option value="${row.id}">${escapeHtml(row.code)} · ${escapeHtml(
                    labelOf(row.premisesType),
                  )}</option>`,
              )
              .join('')}
          </select>
        </label>
        <label class="field">
          <span class="field-label">Инициатор (арендатор)</span>
          <select name="tenantId">
            <option value="">Без арендатора (служба БЦ)</option>
            ${tenants
              .filter((row) => row.active !== false)
              .map(
                (row) => `<option value="${row.id}">${escapeHtml(row.companyName)}</option>`,
              )
              .join('')}
          </select>
        </label>
        <label class="field span-2">
          <span class="field-label">Тема *</span>
          <input name="title" required maxlength="150" placeholder="Например: не работает кондиционер">
        </label>
        <label class="field">
          <span class="field-label">Категория *</span>
          <select name="category">${CATEGORY_OPTIONS.map(
            ([value, label]) => `<option value="${value}">${label}</option>`,
          ).join('')}</select>
        </label>
        <label class="field">
          <span class="field-label">Приоритет *</span>
          <select name="priority">${PRIORITY_OPTIONS.map(
            ([value, label]) => `<option value="${value}">${label}</option>`,
          ).join('')}</select>
        </label>
        <label class="field span-2">
          <span class="field-label">Описание *</span>
          <textarea name="description" required maxlength="4000" rows="4"
                    placeholder="Подробно опишите проблему"></textarea>
        </label>
        <p class="field-hint span-2">
          Заявка сразу попадает в очередь. Если она не будет взята в работу за 24 часа (HIGH)
          или 48 часов (MEDIUM), её приоритет автоматически повысится.
        </p>
        <p class="form-error span-2" role="alert" id="request-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">Создать заявку</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#request-form');
      const errorBox = body.querySelector('#request-error');
      const submitBtn = form.querySelector('button[type="submit"]');

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const value = (name) => String(form.elements.namedItem(name).value || '').trim();
        const payload = {
          premisesId: Number(value('premisesId')) || null,
          tenantId: Number(value('tenantId')) || null,
          title: value('title'),
          description: value('description'),
          category: value('category'),
          priority: value('priority'),
        };

        if (!payload.premisesId) {
          errorBox.textContent = 'Выберите помещение';
          errorBox.hidden = false;
          return;
        }
        if (!payload.title) {
          errorBox.textContent = 'Укажите тему заявки';
          errorBox.hidden = false;
          return;
        }
        if (!payload.description) {
          errorBox.textContent = 'Добавьте описание проблемы';
          errorBox.hidden = false;
          return;
        }

        submitBtn.disabled = true;
        try {
          await api.maintenance.create(payload);
          closeModal();
          toast('Заявка создана и поставлена в очередь', 'success');
          await loadTable(root);
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось создать заявку';
          errorBox.hidden = false;
          submitBtn.disabled = false;
        }
      });
    },
  });
}
