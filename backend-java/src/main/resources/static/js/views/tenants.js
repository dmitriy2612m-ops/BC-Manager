/**
 * Арендаторы (Plan.md, раздел 6.1, п. 4).
 *
 * Реестр контрагентов с живым поиском по названию/ИНН, полнофункциональная
 * карточка арендатора со связанными договорами, быстрым созданием договора,
 * копированием реквизитов и переходом к платежам.
 *
 * Соответствует принципам better-ui, better-typography, better-colors,
 * better-layout, better-accessibility и better-writing.
 *
 * Контракт API (раздел 5.3):
 *  - `GET    /tenants?query=…`         — список/поиск;
 *  - `GET    /tenants/{id}`            — карточка + договоры;
 *  - `POST   /tenants`                 — регистрация;
 *  - `PUT    /tenants/{id}`            — редактирование;
 *  - `PATCH  /tenants/{id}/deactivate` — деактивация.
 */

import { api, getCurrentUser } from '../api.js';
import {
  closeModal,
  copyText,
  emptyHtml,
  escapeHtml,
  formatDate,
  formatDateTime,
  formatMoney,
  loadingHtml,
  openModal,
  renderIcon,
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
        <div>
          <h1>Арендаторы</h1>
          <p class="page-subtitle">Реестр контрагентов, поиск по названию или ИНН и связанные договоры</p>
        </div>
        <div class="page-header-actions">
          <button class="btn btn-primary" type="button" id="add-tenant">
            ${renderIcon('plus')}
            <span>Добавить арендатора</span>
          </button>
        </div>
      </header>

      <div class="filters-card">
        <div class="filters" id="tenant-filters">
          <label class="field filter-search" for="tenant-search-input">
            <span class="field-label">Поиск контрагента</span>
            <div class="input-with-icon">
              <span class="input-icon" aria-hidden="true">${renderIcon('search')}</span>
              <input type="search" id="tenant-search-input" name="query"
                     placeholder="Название компании или ИНН (от 2 символов)"
                     value="${escapeHtml(state.query)}" autocomplete="off">
            </div>
          </label>
          <button class="btn btn-ghost" type="button" id="reset-tenant-search">Сбросить</button>
        </div>
      </div>

      <div id="tenants-table">${loadingHtml()}</div>
    </div>
  `;

  const searchInput = root.querySelector('#tenant-search-input');
  searchInput.addEventListener('input', () => {
    state.query = searchInput.value.trim();
    if (searchTimer) {
      window.clearTimeout(searchTimer);
    }
    searchTimer = window.setTimeout(() => loadTable(root), 250);
  });

  root.querySelector('#reset-tenant-search')?.addEventListener('click', () => {
    state.query = '';
    searchInput.value = '';
    searchInput.focus();
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
      <div class="card card-error">
        <h3 class="card-title">Не удалось загрузить арендаторов</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка сети')}</p>
      </div>`;
    return;
  }

  state.rows = Array.isArray(rows) ? rows : [];

  if (!state.rows.length) {
    container.innerHTML = state.query
      ? emptyHtml(
          'Арендаторы не найдены',
          `По поисковому запросу «${state.query}» ничего не найдено. Проверьте правильность названия или ИНН.`,
        )
      : emptyHtml(
          'Реестр арендаторов пуст',
          'Добавьте первого контрагента, чтобы начать работу с договорами аренды.',
          `<button class="btn btn-primary btn-sm" type="button" id="empty-add-tenant">
             ${renderIcon('plus')} <span>Добавить арендатора</span>
           </button>`,
        );

    container.querySelector('#empty-add-tenant')?.addEventListener('click', () => {
      openTenantForm({ onSaved: () => loadTable(root) });
    });
    return;
  }

  const isAdmin = getCurrentUser()?.role === 'ADMIN';
  const totalCount = state.rows.length;
  const activeCount = state.rows.filter((r) => r.active).length;

  container.innerHTML = `
    <div class="summary-strip">
      <span>Всего контрагентов: <strong class="tabular-nums">${totalCount}</strong></span>
      <span>Действующих: <strong class="tabular-nums">${activeCount}</strong></span>
      ${
        totalCount - activeCount > 0
          ? `<span>Деактивированных: <strong class="tabular-nums">${totalCount - activeCount}</strong></span>`
          : ''
      }
    </div>

    <div class="table-wrap">
      <table class="data-table" aria-label="Таблица арендаторов">
        <thead>
          <tr>
            <th scope="col">Организация / ИП</th>
            <th scope="col">ИНН</th>
            <th scope="col">Контактное лицо</th>
            <th scope="col">Телефон</th>
            <th scope="col">Email</th>
            <th scope="col">Статус</th>
            <th scope="col" class="num">Действия</th>
          </tr>
        </thead>
        <tbody>
          ${state.rows
            .map(
              (row) => `
            <tr class="${row.active ? '' : 'row-muted'}">
              <td>
                <div class="tenant-name-cell">
                  <strong>${escapeHtml(row.companyName)}</strong>
                </div>
              </td>
              <td><span class="tabular-nums">${escapeHtml(row.inn)}</span></td>
              <td>${escapeHtml(row.contactPerson)}</td>
              <td>
                <a class="link-subtle tabular-nums" href="tel:${escapeHtml(row.phone)}">${escapeHtml(row.phone)}</a>
              </td>
              <td>
                <a class="link-subtle" href="mailto:${escapeHtml(row.email)}">${escapeHtml(row.email)}</a>
              </td>
              <td>
                ${
                  row.active
                    ? '<span class="badge badge-success"><span class="badge-dot" aria-hidden="true"></span>Активен</span>'
                    : '<span class="badge badge-neutral"><span class="badge-dot" aria-hidden="true"></span>Неактивен</span>'
                }
              </td>
              <td class="actions-cell">
                <button class="btn btn-secondary btn-sm" type="button" data-card="${row.id}" aria-label="Открыть карточку арендатора ${escapeHtml(row.companyName)}">
                  <span>Карточка</span>
                </button>
                <button class="btn btn-ghost btn-sm" type="button" data-edit="${row.id}" aria-label="Редактировать ${escapeHtml(row.companyName)}">
                  <span>Изменить</span>
                </button>
                ${
                  row.active
                    ? `<button class="btn btn-ghost btn-sm text-danger" type="button" data-deactivate="${row.id}" aria-label="Деактивировать ${escapeHtml(row.companyName)}">Деактивировать</button>`
                    : ''
                }
                ${
                  isAdmin && row.active
                    ? `<button class="btn btn-ghost btn-sm" type="button" data-contract="${row.id}" aria-label="Оформить договор для ${escapeHtml(row.companyName)}">Договор</button>`
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
      if (!window.confirm(`Деактивировать контрагента «${row.companyName}»? История договоров сохранится.`)) {
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
export async function openTenantCard(tenantId) {
  openModal({
    size: 'lg',
    title: 'Карточка арендатора',
    bodyHtml: loadingHtml('Загрузка карточки контрагента…'),
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
    body.innerHTML = `
      <div class="alert alert-danger" role="alert">
        <strong>Не удалось загрузить карточку контрагента</strong>
        <p>${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  const tenant = details?.tenant || {};
  const contracts = Array.isArray(details?.contracts) ? details.contracts : [];
  const activeContracts = contracts.filter((row) => row.status === 'ACTIVE');
  const totalMonthlyRent = activeContracts.reduce((sum, c) => sum + (Number(c.monthlyRent) || 0), 0);
  const isAdmin = getCurrentUser()?.role === 'ADMIN';

  // Обновляем заголовок модального окна с названием организации
  const titleEl = document.getElementById('modal-title');
  if (titleEl) {
    titleEl.innerHTML = `
      <div class="modal-title-with-badge">
        <span>${escapeHtml(tenant.companyName)}</span>
        ${
          tenant.active
            ? '<span class="badge badge-success"><span class="badge-dot" aria-hidden="true"></span>Активен</span>'
            : '<span class="badge badge-neutral"><span class="badge-dot" aria-hidden="true"></span>Неактивен</span>'
        }
      </div>
    `;
  }

  body.innerHTML = `
    <div class="tenant-card-view stack">
      <!-- Сводка KPI -->
      <div class="kpi-grid kpi-grid-3">
        <div class="kpi-card">
          <p class="kpi-label">Всего договоров</p>
          <p class="kpi-value">${contracts.length}</p>
          <p class="kpi-foot">За всю историю</p>
        </div>
        <div class="kpi-card is-success">
          <p class="kpi-label">Активных договоров</p>
          <p class="kpi-value">${activeContracts.length}</p>
          <p class="kpi-foot">${activeContracts.length ? 'Действуют сейчас' : 'Нет действующих'}</p>
        </div>
        <div class="kpi-card">
          <p class="kpi-label">Ежемесячная аренда</p>
          <p class="kpi-value">${formatMoney(totalMonthlyRent)}</p>
          <p class="kpi-foot">По действующим договорам</p>
        </div>
      </div>

      <!-- Реквизиты и контакты -->
      <div class="card card-compact">
        <div class="card-header-row">
          <h4 class="card-title">Реквизиты и контактные данные</h4>
          <button class="btn btn-ghost btn-sm" type="button" id="card-copy-inn" title="Скопировать ИНН в буфер">
            ${renderIcon('copy')}
            <span>ИНН: ${escapeHtml(tenant.inn)}</span>
          </button>
        </div>

        <dl class="detail-list">
          <div>
            <dt>ИНН контрагента</dt>
            <dd>
              <strong class="tabular-nums">${escapeHtml(tenant.inn)}</strong>
            </dd>
          </div>
          <div>
            <dt>Контактное лицо</dt>
            <dd>${escapeHtml(tenant.contactPerson || '—')}</dd>
          </div>
          <div>
            <dt>Телефон</dt>
            <dd>
              ${
                tenant.phone
                  ? `<a class="link-contact tabular-nums" href="tel:${escapeHtml(tenant.phone)}">${renderIcon('phone')} ${escapeHtml(tenant.phone)}</a>`
                  : '—'
              }
            </dd>
          </div>
          <div>
            <dt>Электронная почта</dt>
            <dd>
              ${
                tenant.email
                  ? `<a class="link-contact" href="mailto:${escapeHtml(tenant.email)}">${renderIcon('mail')} ${escapeHtml(tenant.email)}</a>`
                  : '—'
              }
            </dd>
          </div>
          <div>
            <dt>Статус в системе</dt>
            <dd>
              ${
                tenant.active
                  ? '<span class="badge badge-success"><span class="badge-dot" aria-hidden="true"></span>Активен</span>'
                  : '<span class="badge badge-neutral"><span class="badge-dot" aria-hidden="true"></span>Неактивен</span>'
              }
            </dd>
          </div>
          <div>
            <dt>Дата регистрации</dt>
            <dd class="tabular-nums">${formatDateTime(tenant.createdAt)}</dd>
          </div>
        </dl>
      </div>

      <!-- Раздел договоров -->
      <div class="tenant-contracts-section">
        <div class="card-header-row">
          <h4 class="section-title">Договоры аренды (${contracts.length})</h4>
          ${
            isAdmin && tenant.active
              ? `<button class="btn btn-secondary btn-sm" type="button" id="card-new-contract">
                   ${renderIcon('plus')} <span>Новый договор</span>
                 </button>`
              : ''
          }
        </div>

        ${
          contracts.length
            ? `<div class="table-wrap">
                 <table class="data-table" aria-label="Договоры контрагента ${escapeHtml(tenant.companyName)}">
                   <thead>
                     <tr>
                       <th scope="col">Номер</th>
                       <th scope="col">Помещение</th>
                       <th scope="col">Период действия</th>
                       <th scope="col" class="num">Плата, ₽/мес</th>
                       <th scope="col">Статус</th>
                       <th scope="col" class="num">Действия</th>
                     </tr>
                   </thead>
                   <tbody>
                     ${contracts
                       .map(
                         (row) => `
                       <tr>
                         <td><strong class="tabular-nums">${escapeHtml(row.contractNumber)}</strong></td>
                         <td><span class="badge badge-neutral">${escapeHtml(row.premisesCode)}</span></td>
                         <td class="tabular-nums">${formatDate(row.startDate)} — ${formatDate(row.endDate)}</td>
                         <td class="num tabular-nums">${formatMoney(row.monthlyRent)}</td>
                         <td>${statusBadge(row.status)}</td>
                         <td class="actions-cell">
                           <button class="btn btn-ghost btn-sm" type="button" data-view-contract="${row.id}" title="Перейти к договору">
                             ${renderIcon('external')}
                             <span>Договор</span>
                           </button>
                           <button class="btn btn-ghost btn-sm" type="button" data-view-payments="${row.id}" title="Перейти к графику платежей">
                             <span>Платежи</span>
                           </button>
                         </td>
                       </tr>`,
                       )
                       .join('')}
                   </tbody>
                 </table>
               </div>`
            : emptyHtml(
                'Договоров не найдено',
                'У контрагента пока нет оформленных договоров аренды.',
                isAdmin && tenant.active
                  ? `<button class="btn btn-primary btn-sm" type="button" id="card-empty-new-contract">
                       ${renderIcon('plus')} <span>Оформить первый договор</span>
                     </button>`
                  : '',
              )
        }
      </div>
    </div>
  `;

  // Обработчик копирования ИНН
  body.querySelector('#card-copy-inn')?.addEventListener('click', () => {
    copyText(tenant.inn, `ИНН ${tenant.inn} скопирован в буфер обмена`);
  });

  // Переход к договору
  body.querySelectorAll('[data-view-contract]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const contractId = btn.dataset.viewContract;
      closeModal();
      window.location.hash = '#/contracts';
      window.dispatchEvent(
        new CustomEvent('bc:route-context', {
          detail: { route: 'contracts', context: { contractId } },
        }),
      );
    });
  });

  // Переход к платежам
  body.querySelectorAll('[data-view-payments]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const contractId = btn.dataset.viewPayments;
      closeModal();
      window.location.hash = '#/payments';
      window.dispatchEvent(
        new CustomEvent('bc:route-context', {
          detail: { route: 'payments', context: { contractId } },
        }),
      );
    });
  });

  const startContractCreation = () => {
    closeModal();
    window.location.hash = '#/contracts';
    window.dispatchEvent(
      new CustomEvent('bc:route-context', {
        detail: { route: 'contracts', context: { tenantId: tenant.id } },
      }),
    );
  };

  body.querySelector('#card-new-contract')?.addEventListener('click', startContractCreation);
  body.querySelector('#card-empty-new-contract')?.addEventListener('click', startContractCreation);

  // Подвал модального окна
  const footer = document.getElementById('modal-footer');
  if (footer) {
    footer.innerHTML = `
      <div class="modal-footer-actions">
        <button class="btn btn-secondary btn-sm" type="button" id="card-edit-tenant">
          ${renderIcon('edit')}
          <span>Редактировать</span>
        </button>
        ${
          isAdmin && tenant.active
            ? `<button class="btn btn-primary btn-sm" type="button" id="card-footer-new-contract">
                 ${renderIcon('plus')}
                 <span>Создать договор</span>
               </button>`
            : ''
        }
        <button class="btn btn-ghost btn-sm" type="button" id="card-close-btn">Закрыть</button>
      </div>
    `;

    footer.querySelector('#card-close-btn')?.addEventListener('click', closeModal);
    footer.querySelector('#card-edit-tenant')?.addEventListener('click', () => {
      openTenantForm({
        tenant,
        onSaved: () => {
          loadCard(body, tenantId);
          const root = document.getElementById('view-root');
          if (root) {
            loadTable(root);
          }
        },
      });
    });
    footer.querySelector('#card-footer-new-contract')?.addEventListener('click', startContractCreation);
  }
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
    size: 'md',
    title: isEdit ? `Редактирование: ${escapeHtml(value.companyName)}` : 'Регистрация нового арендатора',
    bodyHtml: `
      <form id="tenant-form" class="form-grid" novalidate>
        <label class="field span-2" for="tenant-company-name">
          <span class="field-label">Наименование организации / ИП *</span>
          <input id="tenant-company-name" name="companyName" required maxlength="150"
                 placeholder="Например, ООО «Северные технологии»"
                 value="${escapeHtml(value.companyName || '')}">
        </label>
        <label class="field" for="tenant-inn-input">
          <span class="field-label">ИНН * (10 или 12 цифр)</span>
          <input id="tenant-inn-input" name="inn" required inputmode="numeric" pattern="\\d{10}|\\d{12}" maxlength="12"
                 placeholder="7701234567"
                 value="${escapeHtml(value.inn || '')}">
        </label>
        <label class="field" for="tenant-contact-person">
          <span class="field-label">Контактное лицо *</span>
          <input id="tenant-contact-person" name="contactPerson" required maxlength="120"
                 placeholder="Иванов Иван Иванович"
                 value="${escapeHtml(value.contactPerson || '')}">
        </label>
        <label class="field" for="tenant-phone-input">
          <span class="field-label">Телефон *</span>
          <input id="tenant-phone-input" name="phone" required maxlength="30" type="tel"
                 placeholder="+7 (495) 000-00-00"
                 value="${escapeHtml(value.phone || '')}">
        </label>
        <label class="field" for="tenant-email-input">
          <span class="field-label">Email *</span>
          <input id="tenant-email-input" name="email" type="email" required maxlength="120"
                 placeholder="contact@company.ru"
                 value="${escapeHtml(value.email || '')}">
        </label>
        <p class="form-error span-2" role="alert" id="tenant-form-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">${isEdit ? 'Сохранить изменения' : 'Зарегистрировать'}</button>
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
            toast(`Данные контрагента «${payload.companyName}» обновлены`, 'success');
          } else {
            await api.tenants.create(payload);
            toast(`Арендатор «${payload.companyName}» успешно зарегистрирован`, 'success');
          }
          closeModal();
          onSaved();
        } catch (error) {
          submitBtn.disabled = false;
          errorBox.textContent = error?.message || 'Не удалось сохранить данные арендатора';
          errorBox.hidden = false;
        }
      });
    },
  });
}

/**
 * Считать значения из формы арендатора.
 *
 * @param {HTMLFormElement} form форма
 * @returns {object} тело запроса `TenantRequest`
 */
function readTenantForm(form) {
  const data = new FormData(form);
  return {
    companyName: String(data.get('companyName') || '').trim(),
    inn: String(data.get('inn') || '').trim(),
    contactPerson: String(data.get('contactPerson') || '').trim(),
    phone: String(data.get('phone') || '').trim(),
    email: String(data.get('email') || '').trim(),
  };
}

/**
 * Валидация полей арендатора на клиенте.
 *
 * @param {object} payload данные
 * @returns {string|null} сообщение об ошибке или `null`
 */
function validateTenant(payload) {
  if (!payload.companyName) {
    return 'Укажите наименование организации или ИП';
  }
  if (!/^\d{10}$|^\d{12}$/.test(payload.inn)) {
    return 'ИНН должен состоять ровно из 10 (юрлицо) или 12 (ИП) цифр';
  }
  if (!payload.contactPerson) {
    return 'Укажите контактное лицо';
  }
  if (!payload.phone) {
    return 'Укажите контактный номер телефона';
  }
  if (!payload.email || !payload.email.includes('@')) {
    return 'Укажите корректный адрес электронной почты';
  }
  return null;
}
