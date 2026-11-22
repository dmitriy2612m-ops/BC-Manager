/**
 * Арендаторы (Plan.md, раздел 6.1, п. 4).
 *
 * Реестр контрагентов с живым поиском по названию/ИНН, карточка арендатора
 * с его договорами, добавление/редактирование и деактивация.
 *
 * Контракт API (раздел 5.3):
 *  - `GET    /tenants?query=…`       — список/поиск;
 *  - `GET    /tenants/{id}`          — карточка + договоры;
 *  - `POST   /tenants`               — регистрация;
 *  - `PUT    /tenants/{id}`          — редактирование;
 *  - `PATCH  /tenants/{id}/deactivate` — деактивация.
 */

import { api, getCurrentUser } from '../api.js';
import {
  closeModal,
  emptyHtml,
  escapeHtml,
  formatDate,
  formatDateTime,
  formatMoney,
  loadingHtml,
  openModal,
  statusBadge,
  toast,
} from '../ui.js';

/** Состояние экрана: поисковый запрос и последняя выдача. */
const state = {
  query: '',
  rows: [],
};

/** Таймер отложенного живого поиска. */
let searchTimer = null;

/**
 * Отрисовать экран арендаторов.
 *
 * @param {HTMLElement} root контейнер контента
 * @param {{context?: object}} options окружение
 */
export async function renderTenants(root, { context } = {}) {
  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Арендаторы</h1>
        <p class="page-subtitle">Реестр контрагентов, поиск по названию или ИНН и связанные договоры</p>
        <div class="page-header-actions">
          <button class="btn btn-primary" type="button" id="add-tenant">Добавить арендатора</button>
        </div>
      </header>

      <div class="filters" id="tenant-filters">
        <label class="field filter-search">
          <span class="field-label">Поиск</span>
          <input type="search" name="query" placeholder="Название компании или ИНН"
                 value="${escapeHtml(state.query)}" autocomplete="off">
        </label>
        <button class="btn btn-ghost" type="button" id="reset-tenant-search">Сбросить</button>
      </div>

      <div id="tenants-table">${loadingHtml()}</div>
    </div>
  `;

  const searchInput = root.querySelector('#tenant-filters input[name="query"]');
  searchInput.addEventListener('input', () => {
    state.query = searchInput.value.trim();
    if (searchTimer) {
      window.clearTimeout(searchTimer);
    }
    searchTimer = window.setTimeout(() => loadTable(root), 280);
  });

  root.querySelector('#reset-tenant-search')?.addEventListener('click', () => {
    state.query = '';
    searchInput.value = '';
    loadTable(root);
  });

  root.querySelector('#add-tenant')?.addEventListener('click', () => {
    openTenantForm({ onSaved: () => loadTable(root) });
  });

  await loadTable(root);

  // Переход из другого экрана: сразу открыть карточку арендатора после отрисовки реестра.
  if (context?.tenantId) {
    await openTenantCard(Number(context.tenantId));
  }
}

/**
 * Загрузить реестр арендаторов.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function loadTable(root) {
  const container = root.querySelector('#tenants-table');
  if (!container) {
    return;
  }
  container.innerHTML = loadingHtml();

  let rows;
  try {
    rows = await api.tenants.list(state.query ? { query: state.query } : {});
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    container.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить арендаторов</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  state.rows = Array.isArray(rows) ? rows : [];

  if (!state.rows.length) {
    container.innerHTML = state.query
      ? emptyHtml('Арендаторы не найдены', `По запросу «${state.query}» ничего не найдено.`)
      : emptyHtml('Реестр пуст', 'Добавьте первого арендатора, чтобы начать работу.');
    return;
  }

  const isAdmin = getCurrentUser()?.role === 'ADMIN';

  container.innerHTML = `
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Организация</th>
            <th>ИНН</th>
            <th>Контактное лицо</th>
            <th>Телефон</th>
            <th>Email</th>
            <th>Статус</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          ${state.rows
            .map(
              (row) => `
            <tr class="${row.active ? '' : 'row-muted'}">
              <td><strong>${escapeHtml(row.companyName)}</strong></td>
              <td>${escapeHtml(row.inn)}</td>
              <td>${escapeHtml(row.contactPerson)}</td>
              <td>${escapeHtml(row.phone)}</td>
              <td>${escapeHtml(row.email)}</td>
              <td>${
                row.active
                  ? '<span class="badge badge-success">Активен</span>'
                  : '<span class="badge badge-neutral">Неактивен</span>'
              }</td>
              <td class="actions-cell">
                <button class="btn btn-secondary btn-sm" type="button" data-card="${row.id}">Карточка</button>
                <button class="btn btn-ghost btn-sm" type="button" data-edit="${row.id}">Изменить</button>
                ${
                  row.active
                    ? `<button class="btn btn-ghost btn-sm" type="button" data-deactivate="${row.id}">Деактивировать</button>`
                    : ''
                }
                ${
                  isAdmin
                    ? `<button class="btn btn-ghost btn-sm" type="button" data-contract="${row.id}">Договор</button>`
                    : ''
                }
              </td>
            </tr>`,
            )
            .join('')}
        </tbody>
      </table>
    </div>
  `;

  const find = (id) => state.rows.find((item) => String(item.id) === String(id));

  container.querySelectorAll('[data-card]').forEach((button) => {
    button.addEventListener('click', () => {
      openTenantCard(Number(button.dataset.card));
    });
  });

  container.querySelectorAll('[data-edit]').forEach((button) => {
    button.addEventListener('click', () => {
      const row = find(button.dataset.edit);
      if (row) {
        openTenantForm({ tenant: row, onSaved: () => loadTable(root) });
      }
    });
  });

  container.querySelectorAll('[data-deactivate]').forEach((button) => {
    button.addEventListener('click', async () => {
      const row = find(button.dataset.deactivate);
      if (!row) {
        return;
      }
      if (!window.confirm(`Деактивировать контрагента «${row.companyName}»?`)) {
        return;
      }
      button.disabled = true;
      try {
        await api.tenants.deactivate(row.id);
        toast(`Контрагент «${row.companyName}» деактивирован`, 'success');
        await loadTable(root);
      } catch (error) {
        toast(error?.message || 'Не удалось деактивировать контрагента', 'error');
        button.disabled = false;
      }
    });
  });

  // Быстрый переход к мастеру договора с предзаполненным арендатором.
  container.querySelectorAll('[data-contract]').forEach((button) => {
    button.addEventListener('click', () => {
      const row = find(button.dataset.contract);
      if (!row) {
        return;
      }
      window.location.hash = '#/contracts';
      window.dispatchEvent(
        new CustomEvent('bc:route-context', {
          detail: { route: 'contracts', context: { tenantId: row.id } },
        }),
      );
    });
  });
}

/**
 * Открыть карточку арендатора с договорами.
 *
 * @param {number} tenantId идентификатор
 */
async function openTenantCard(tenantId) {
  openModal({
    title: 'Карточка арендатора',
    bodyHtml: loadingHtml(),
    footerHtml: '',
    onMount(body) {
      loadCard(body, tenantId);
    },
  });
}

/**
 * Заполнить тело карточки арендатора.
 *
 * @param {HTMLElement} body контейнер модального окна
 * @param {number} tenantId идентификатор
 */
async function loadCard(body, tenantId) {
  let details;
  try {
    details = await api.tenants.get(tenantId);
  } catch (error) {
    body.innerHTML = `<p class="form-error">${escapeHtml(error?.message || 'Не удалось загрузить карточку')}</p>`;
    return;
  }

  const tenant = details?.tenant || {};
  const contracts = Array.isArray(details?.contracts) ? details.contracts : [];
  const activeContracts = contracts.filter((row) => row.status === 'ACTIVE');

  body.innerHTML = `
    <dl class="detail-list">
      <div><dt>Организация</dt><dd>${escapeHtml(tenant.companyName)}</dd></div>
      <div><dt>ИНН</dt><dd>${escapeHtml(tenant.inn)}</dd></div>
      <div><dt>Контактное лицо</dt><dd>${escapeHtml(tenant.contactPerson)}</dd></div>
      <div><dt>Телефон</dt><dd>${escapeHtml(tenant.phone)}</dd></div>
      <div><dt>Email</dt><dd>${escapeHtml(tenant.email)}</dd></div>
      <div><dt>Статус</dt><dd>${
        tenant.active
          ? '<span class="badge badge-success">Активен</span>'
          : '<span class="badge badge-neutral">Неактивен</span>'
      }</dd></div>
      <div><dt>Зарегистрирован</dt><dd>${formatDateTime(tenant.createdAt)}</dd></div>
      <div><dt>Договоров</dt><dd>${contracts.length} (активных: ${activeContracts.length})</dd></div>
    </dl>

    <h4 class="modal-section-title">Договоры арендатора</h4>
    ${
      contracts.length
        ? `<div class="table-wrap">
             <table class="data-table">
               <thead>
                 <tr>
                   <th>Номер</th>
                   <th>Помещение</th>
                   <th>Период</th>
                   <th class="num">Плата, ₽/мес</th>
                   <th>Статус</th>
                 </tr>
               </thead>
               <tbody>
                 ${contracts
                   .map(
                     (row) => `
                   <tr>
                     <td><strong>${escapeHtml(row.contractNumber)}</strong></td>
                     <td>${escapeHtml(row.premisesCode)}</td>
                     <td>${formatDate(row.startDate)} — ${formatDate(row.endDate)}</td>
                     <td class="num">${formatMoney(row.monthlyRent)}</td>
                     <td>${statusBadge(row.status)}</td>
                   </tr>`,
                   )
                   .join('')}
               </tbody>
             </table>
           </div>`
        : emptyHtml('Договоров нет', 'У контрагента пока нет оформленных договоров.')
    }
  `;

}

/**
 * Модальное окно создания/редактирования арендатора.
 *
 * @param {{tenant?: object, onSaved: Function}} options параметры окна
 */
function openTenantForm({ tenant, onSaved }) {
  const isEdit = Boolean(tenant);
  const value = tenant || {};

  openModal({
    title: isEdit ? `Арендатор ${value.companyName}` : 'Новый арендатор',
    bodyHtml: `
      <form id="tenant-form" class="form-grid" novalidate>
        <label class="field span-2">
          <span class="field-label">Наименование организации / ИП *</span>
          <input name="companyName" required maxlength="150"
                 value="${escapeHtml(value.companyName || '')}">
        </label>
        <label class="field">
          <span class="field-label">ИНН * (10 или 12 цифр)</span>
          <input name="inn" required inputmode="numeric" pattern="\\d{10}|\\d{12}" maxlength="12"
                 value="${escapeHtml(value.inn || '')}">
        </label>
        <label class="field">
          <span class="field-label">Контактное лицо *</span>
          <input name="contactPerson" required maxlength="120"
                 value="${escapeHtml(value.contactPerson || '')}">
        </label>
        <label class="field">
          <span class="field-label">Телефон *</span>
          <input name="phone" required maxlength="30" type="tel"
                 value="${escapeHtml(value.phone || '')}">
        </label>
        <label class="field">
          <span class="field-label">Email *</span>
          <input name="email" type="email" required maxlength="120" id="tenant-email"
                 value="${escapeHtml(value.email || '')}">
        </label>
        <p class="form-error span-2" role="alert" id="tenant-form-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">${isEdit ? 'Сохранить' : 'Зарегистрировать'}</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#tenant-form');
      const errorBox = body.querySelector('#tenant-form-error');
      const submitBtn = form.querySelector('button[type="submit"]');

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const payload = readTenantForm(form);
        const invalid = validateTenant(payload);
        if (invalid) {
          errorBox.textContent = invalid;
          errorBox.hidden = false;
          return;
        }

        submitBtn.disabled = true;
        try {
          if (isEdit) {
            await api.tenants.update(value.id, payload);
            toast(`Арендатор «${payload.companyName}» обновлён`, 'success');
          } else {
            await api.tenants.create(payload);
            toast(`Арендатор «${payload.companyName}» зарегистрирован`, 'success');
          }
          closeModal();
          onSaved();
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось сохранить арендатора';
          errorBox.hidden = false;
        } finally {
          submitBtn.disabled = false;
        }
      });
    },
  });
}

/**
 * Собрать payload арендатора из формы.
 *
 * @param {HTMLFormElement} form форма
 * @returns {object} тело запроса `TenantRequest`
 */
function readTenantForm(form) {
  const read = (name) => String(form.elements.namedItem(name).value || '').trim();
  return {
    companyName: read('companyName'),
    inn: read('inn'),
    contactPerson: read('contactPerson'),
    phone: read('phone'),
    email: read('email'),
  };
}

/**
 * Клиентская валидация арендатора.
 *
 * @param {object} payload тело запроса
 * @returns {string|null} сообщение об ошибке или `null`
 */
function validateTenant(payload) {
  if (!payload.companyName) {
    return 'Укажите наименование организации';
  }
  if (!/^\d{10}$|^\d{12}$/.test(payload.inn)) {
    return 'ИНН должен состоять из 10 или 12 цифр';
  }
  if (!payload.contactPerson) {
    return 'Укажите контактное лицо';
  }
  if (!payload.phone) {
    return 'Укажите телефон';
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(payload.email)) {
    return 'Укажите корректный email';
  }
  return null;
}
