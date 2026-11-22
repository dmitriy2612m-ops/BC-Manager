/**
 * Помещения и умный подбор (Plan.md, раздел 6.1, п. 3).
 *
 * Две вкладки:
 *  1. Реестр помещений с фильтрами по корпусу, этажу, типу и статусу,
 *     создание/редактирование/удаление для роли `ADMIN`.
 *  2. Умный подбор: критерии → `POST /api/v1/premises/match` (Алгоритм 2),
 *     ранжированный список с индикатором Score и переходом к договору.
 */

import { api, getCurrentUser } from '../api.js';
import {
  addDaysIso,
  closeModal,
  emptyHtml,
  escapeHtml,
  formatArea,
  formatMoney,
  formatPercent,
  labelOf,
  loadingHtml,
  openModal,
  statusBadge,
  toast,
  todayIso,
} from '../ui.js';

const TYPE_OPTIONS = [
  ['', 'Все типы'],
  ['OFFICE', 'Офис'],
  ['RETAIL', 'Торговое'],
  ['CONFERENCE', 'Переговорная'],
  ['PARKING', 'Паркинг'],
];

const STATUS_OPTIONS = [
  ['', 'Все статусы'],
  ['AVAILABLE', 'Свободно'],
  ['RENTED', 'Арендовано'],
  ['RESERVED', 'Зарезервировано'],
  ['MAINTENANCE', 'На обслуживании'],
];

/** Текущее состояние экрана: фильтры и активная вкладка. */
const state = {
  filters: { type: '', status: '', floor: '', building: '' },
  tab: 'registry',
  matchResult: null,
  matchCriteria: null,
};

/**
 * Отрисовать экран помещений.
 *
 * @param {HTMLElement} root контейнер контента
 * @param {{navigate: Function, context?: object}} options окружение
 */
export async function renderPremises(root, { navigate, context } = {}) {
  const user = getCurrentUser();
  const isAdmin = user?.role === 'ADMIN';

  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Помещения</h1>
        <p class="page-subtitle">Реестр коммерческих площадей и умный подбор под требования арендатора</p>
        ${
          isAdmin
            ? '<div class="page-header-actions"><button class="btn btn-primary" type="button" id="add-premises">Добавить помещение</button></div>'
            : '<div class="page-header-actions"><button class="btn btn-secondary" type="button" id="export-premises">Экспорт в CSV</button></div>'
        }
      </header>

      <div class="tabs" role="tablist">
        <button class="tab" type="button" role="tab" data-tab="registry" id="tab-registry">
          Реестр помещений
        </button>
        <button class="tab" type="button" role="tab" data-tab="match" id="tab-match">
          Умный подбор
        </button>
      </div>

      <section class="tab-panel" data-panel="registry" role="tabpanel">
        <div class="filters" id="premises-filters">
          <label class="field">
            <span class="field-label">Тип</span>
            <select name="type">${TYPE_OPTIONS.map(
              ([value, label]) =>
                `<option value="${value}">${label}</option>`,
            ).join('')}</select>
          </label>
          <label class="field">
            <span class="field-label">Статус</span>
            <select name="status">${STATUS_OPTIONS.map(
              ([value, label]) =>
                `<option value="${value}">${label}</option>`,
            ).join('')}</select>
          </label>
          <label class="field">
            <span class="field-label">Этаж</span>
            <input type="number" name="floor" placeholder="Любой" step="1">
          </label>
          <label class="field">
            <span class="field-label">Корпус</span>
            <input type="text" name="building" placeholder="Любой">
          </label>
          <button class="btn btn-secondary" type="button" id="apply-filters">Применить</button>
          <button class="btn btn-ghost" type="button" id="reset-filters">Сбросить</button>
        </div>
        <div id="premises-table">${loadingHtml()}</div>
      </section>

      <section class="tab-panel" data-panel="match" role="tabpanel" hidden>
        <div class="grid-2">
          <form class="card" id="match-form">
            <h3 class="card-title">Критерии подбора</h3>
            <p class="card-subtitle">Укажите требования — система отранжирует свободные помещения</p>
            <div class="form-grid">
              <label class="field">
                <span class="field-label">Тип помещения</span>
                <select name="type">${TYPE_OPTIONS.map(
                  ([value, label]) =>
                    `<option value="${value}">${label}</option>`,
                ).join('')}</select>
              </label>
              <label class="field">
                <span class="field-label">Целевая площадь, м²</span>
                <input type="number" name="targetArea" min="0" step="0.1" placeholder="100">
              </label>
              <label class="field">
                <span class="field-label">Мин. площадь, м²</span>
                <input type="number" name="minArea" min="0" step="0.1" placeholder="Не важно">
              </label>
              <label class="field">
                <span class="field-label">Макс. площадь, м²</span>
                <input type="number" name="maxArea" min="0" step="0.1" placeholder="Не важно">
              </label>
              <label class="field">
                <span class="field-label">Макс. бюджет, ₽/мес</span>
                <input type="number" name="maxBudget" min="0" step="100" placeholder="Не важно">
              </label>
              <span></span>
              <label class="field">
                <span class="field-label">Дата начала</span>
                <input type="date" name="startDate" required>
              </label>
              <label class="field">
                <span class="field-label">Дата окончания</span>
                <input type="date" name="endDate" required>
              </label>
            </div>
            <div class="form-actions">
              <button class="btn btn-primary" type="submit">Подобрать</button>
            </div>
          </form>

          <section class="card">
            <h3 class="card-title">Результат подбора</h3>
            <p class="card-subtitle">Score — степень соответствия требованиям (0–100)</p>
            <div id="match-results">${emptyHtml('Заполните критерии', 'Список кандидатов появится после подбора.')}</div>
          </section>
        </div>
      </section>
    </div>
  `;

  const matchForm = root.querySelector('#match-form');
  const today = todayIso();
  matchForm.elements.namedItem('startDate').value = today;
  matchForm.elements.namedItem('endDate').value = addDaysIso(today, 365);

  // Предзаполнение из «Умного подбора» (переход из экрана договоров в Сессии 8).
  if (context?.matchCriteria) {
    applyMatchCriteria(matchForm, context.matchCriteria);
  }

  // Восстановление ранее выбранной вкладки.
  switchTab(root, state.tab);

  root.querySelectorAll('.tab').forEach((button) => {
    button.addEventListener('click', () => {
      switchTab(root, button.dataset.tab);
    });
  });

  root.querySelector('#apply-filters')?.addEventListener('click', () => {
    readFilters(root);
    loadTable(root);
  });

  root.querySelector('#reset-filters')?.addEventListener('click', () => {
    state.filters = { type: '', status: '', floor: '', building: '' };
    const form = root.querySelector('#premises-filters');
    form.reset();
    loadTable(root);
  });

  root.querySelector('#add-premises')?.addEventListener('click', () => {
    openPremisesForm({ onSaved: () => loadTable(root) });
  });

  root.querySelector('#export-premises')?.addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
      await api.analytics.exportPremisesCsv();
      toast('Файл premises.csv сформирован', 'success');
    } catch (error) {
      toast(error?.message || 'Не удалось выгрузить CSV', 'error');
    } finally {
      button.disabled = false;
    }
  });

  matchForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    await runMatch(root, matchForm, navigate);
  });

  if (state.matchResult) {
    renderMatchResults(root, navigate);
    switchTab(root, 'match');
  }

  await loadTable(root);
}

/**
 * Переключить вкладку.
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {'registry'|'match'} tab имя вкладки
 */
function switchTab(root, tab) {
  state.tab = tab === 'match' ? 'match' : 'registry';
  root.querySelectorAll('.tab').forEach((button) => {
    const active = button.dataset.tab === state.tab;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', active ? 'true' : 'false');
  });
  root.querySelectorAll('.tab-panel').forEach((panel) => {
    panel.hidden = panel.dataset.panel !== state.tab;
  });
}

/**
 * Прочитать фильтры реестра из формы.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
function readFilters(root) {
  const form = root.querySelector('#premises-filters');
  state.filters = {
    type: form.elements.namedItem('type').value,
    status: form.elements.namedItem('status').value,
    floor: form.elements.namedItem('floor').value.trim(),
    building: form.elements.namedItem('building').value.trim(),
  };
}

/**
 * Загрузить реестр помещений и отрисовать таблицу.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function loadTable(root) {
  const container = root.querySelector('#premises-table');
  if (!container) {
    return;
  }
  container.innerHTML = loadingHtml();

  let rows;
  try {
    rows = await api.premises.list(state.filters);
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    container.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить помещения</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  if (!Array.isArray(rows) || rows.length === 0) {
    container.innerHTML = emptyHtml(
      'Помещения не найдены',
      'Измените фильтры или добавьте новое помещение.',
    );
    return;
  }

  const user = getCurrentUser();
  const isAdmin = user?.role === 'ADMIN';

  container.innerHTML = `
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Код</th>
            <th>Тип</th>
            <th>Корпус / этаж</th>
            <th class="num">Площадь</th>
            <th class="num">Ставка, ₽/м²</th>
            <th class="num">База, ₽/мес</th>
            <th class="num">С НДС, ₽/мес</th>
            <th>Статус</th>
            ${isAdmin ? '<th></th>' : ''}
          </tr>
        </thead>
        <tbody>
          ${rows
            .map((row) => {
              const withVat = withVatOf(row);
              return `
            <tr>
              <td><strong>${escapeHtml(row.code)}</strong></td>
              <td>${escapeHtml(labelOf(row.premisesType))}</td>
              <td>${escapeHtml(row.building)} · ${escapeHtml(row.floor)}</td>
              <td class="num">${formatArea(row.areaSqm)}</td>
              <td class="num">${formatMoney(row.baseRatePerSqm)}</td>
              <td class="num">${formatMoney(row.baseMonthlyCost)}</td>
              <td class="num">${formatMoney(withVat)}</td>
              <td>${statusBadge(row.status)}</td>
              ${
                isAdmin
                  ? `<td class="actions-cell">
                      <button class="btn btn-secondary btn-sm" type="button" data-edit="${row.id}">Изменить</button>
                      <button class="btn btn-ghost btn-sm" type="button" data-delete="${row.id}">Удалить</button>
                    </td>`
                  : ''
              }
            </tr>`;
            })
            .join('')}
        </tbody>
      </table>
    </div>
  `;

  container.querySelectorAll('[data-edit]').forEach((button) => {
    button.addEventListener('click', () => {
      const row = rows.find((item) => String(item.id) === button.dataset.edit);
      if (row) {
        openPremisesForm({ premises: row, onSaved: () => loadTable(root) });
      }
    });
  });

  container.querySelectorAll('[data-delete]').forEach((button) => {
    button.addEventListener('click', async () => {
      const row = rows.find((item) => String(item.id) === button.dataset.delete);
      if (!row) {
        return;
      }
      if (!window.confirm(`Удалить помещение ${row.code}?`)) {
        return;
      }
      button.disabled = true;
      try {
        await api.premises.remove(row.id);
        toast(`Помещение ${row.code} удалено`, 'success');
        await loadTable(root);
      } catch (error) {
        toast(error?.message || 'Не удалось удалить помещение', 'error');
        button.disabled = false;
      }
    });
  });
}

/**
 * Оценка месячной стоимости с НДС для реестра.
 *
 * @param {object} row карточка помещения
 * @returns {number|null} сумма
 */
function withVatOf(row) {
  const base = Number(row.baseMonthlyCost);
  const opex = Number(row.opexCost);
  if (Number.isNaN(base) || Number.isNaN(opex)) {
    return null;
  }
  return Math.round((base + opex) * 1.2 * 100) / 100;
}

/**
 * Модальное окно создания/редактирования помещения.
 *
 * @param {{premises?: object, onSaved: Function}} options параметры окна
 */
function openPremisesForm({ premises, onSaved }) {
  const isEdit = Boolean(premises);
  const value = premises || {};

  openModal({
    size: 'md',
    title: isEdit ? `Помещение ${value.code}` : 'Новое помещение',
    bodyHtml: `
      <form id="premises-form" class="form-grid" novalidate>
        <label class="field">
          <span class="field-label">Код *</span>
          <input name="code" required maxlength="30" value="${escapeHtml(value.code || '')}">
        </label>
        <label class="field">
          <span class="field-label">Корпус *</span>
          <input name="building" required maxlength="50" value="${escapeHtml(value.building || 'Корпус А')}">
        </label>
        <label class="field">
          <span class="field-label">Этаж *</span>
          <input type="number" name="floor" required step="1" value="${escapeHtml(value.floor ?? 1)}">
        </label>
        <label class="field">
          <span class="field-label">Тип *</span>
          <select name="premisesType">
            ${TYPE_OPTIONS.filter(([code]) => code)
              .map(
                ([code, label]) =>
                  `<option value="${code}" ${
                    value.premisesType === code ? 'selected' : ''
                  }>${label}</option>`,
              )
              .join('')}
          </select>
        </label>
        <label class="field">
          <span class="field-label">Площадь, м² *</span>
          <input type="number" name="areaSqm" required min="0.01" step="0.01"
                 value="${escapeHtml(value.areaSqm ?? '')}">
        </label>
        <label class="field">
          <span class="field-label">Базовая ставка, ₽/м² *</span>
          <input type="number" name="baseRatePerSqm" required min="0.01" step="0.01"
                 value="${escapeHtml(value.baseRatePerSqm ?? '')}">
        </label>
        <label class="field">
          <span class="field-label">Статус</span>
          <select name="status">
            ${STATUS_OPTIONS.filter(([code]) => code)
              .map(
                ([code, label]) =>
                  `<option value="${code}" ${
                    value.status === code ? 'selected' : ''
                  }>${label}</option>`,
              )
              .join('')}
          </select>
        </label>
        <label class="field">
          <span class="field-label">Вместимость, чел. (переговорная)</span>
          <input type="number" name="capacityPeople" min="0" step="1"
                 value="${escapeHtml(value.capacityPeople ?? '')}">
        </label>
        <label class="field checkbox-field">
          <input type="checkbox" name="hasFurniture" ${value.hasFurniture ? 'checked' : ''}>
          <span>Мебель (офис, +10%)</span>
        </label>
        <label class="field checkbox-field">
          <input type="checkbox" name="hasShowcase" ${value.hasShowcase ? 'checked' : ''}>
          <span>Витрина / отдельный вход (+15%)</span>
        </label>
        <label class="field checkbox-field span-2">
          <input type="checkbox" name="covered" ${value.covered !== false ? 'checked' : ''}>
          <span>Крытый паркинг (+12%)</span>
        </label>
        <label class="field span-2">
          <span class="field-label">Описание</span>
          <textarea name="description" maxlength="2000">${escapeHtml(value.description || '')}</textarea>
        </label>
        <p class="form-error span-2" role="alert" id="premises-form-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">${isEdit ? 'Сохранить' : 'Создать'}</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#premises-form');
      const errorBox = body.querySelector('#premises-form-error');
      const submitBtn = form.querySelector('button[type="submit"]');

      if (value.premisesType) {
        form.elements.namedItem('premisesType').value = value.premisesType;
      }
      if (value.status) {
        form.elements.namedItem('status').value = value.status;
      }

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const payload = readPremisesForm(form);
        const invalid = validatePremises(payload);
        if (invalid) {
          errorBox.textContent = invalid;
          errorBox.hidden = false;
          return;
        }

        submitBtn.disabled = true;
        try {
          if (isEdit) {
            await api.premises.update(value.id, payload);
            toast(`Помещение ${payload.code} обновлено`, 'success');
          } else {
            await api.premises.create(payload);
            toast(`Помещение ${payload.code} создано`, 'success');
          }
          closeModal();
          onSaved();
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось сохранить помещение';
          errorBox.hidden = false;
        } finally {
          submitBtn.disabled = false;
        }
      });
    },
  });
}

/**
 * Собрать payload помещения из формы.
 *
 * @param {HTMLFormElement} form форма
 * @returns {object} тело запроса `PremisesRequest`
 */
function readPremisesForm(form) {
  const read = (name) => form.elements.namedItem(name);
  const numberOrNull = (name) => {
    const raw = String(read(name).value || '').trim();
    if (!raw) {
      return null;
    }
    const num = Number(raw);
    return Number.isNaN(num) ? null : num;
  };

  return {
    code: String(read('code').value || '').trim(),
    building: String(read('building').value || '').trim(),
    floor: Number(read('floor').value),
    premisesType: read('premisesType').value,
    areaSqm: numberOrNull('areaSqm'),
    baseRatePerSqm: numberOrNull('baseRatePerSqm'),
    status: read('status').value || 'AVAILABLE',
    hasFurniture: read('hasFurniture').checked,
    hasShowcase: read('hasShowcase').checked,
    capacityPeople: numberOrNull('capacityPeople'),
    covered: read('covered').checked,
    description: String(read('description').value || '').trim() || null,
  };
}

/**
 * Клиентская валидация перед отправкой.
 *
 * @param {object} payload тело запроса
 * @returns {string|null} сообщение об ошибке или `null`
 */
function validatePremises(payload) {
  if (!payload.code) {
    return 'Укажите код помещения';
  }
  if (!payload.building) {
    return 'Укажите корпус';
  }
  if (!Number.isFinite(payload.floor)) {
    return 'Укажите этаж';
  }
  if (!payload.areaSqm || payload.areaSqm <= 0) {
    return 'Площадь должна быть больше нуля';
  }
  if (!payload.baseRatePerSqm || payload.baseRatePerSqm <= 0) {
    return 'Базовая ставка должна быть больше нуля';
  }
  return null;
}

/**
 * Выполнить умный подбор (Алгоритм 2).
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {HTMLFormElement} form форма критериев
 * @param {Function} navigate функция навигации
 */
async function runMatch(root, form, navigate) {
  const read = (name) => form.elements.namedItem(name);
  const numberOrNull = (name) => {
    const raw = String(read(name).value || '').trim();
    if (!raw) {
      return null;
    }
    const num = Number(raw);
    return Number.isNaN(num) ? null : num;
  };

  const criteria = {
    type: read('type').value || null,
    targetArea: numberOrNull('targetArea'),
    minArea: numberOrNull('minArea'),
    maxArea: numberOrNull('maxArea'),
    maxBudget: numberOrNull('maxBudget'),
    startDate: read('startDate').value,
    endDate: read('endDate').value,
  };

  if (!criteria.startDate || !criteria.endDate) {
    toast('Укажите период аренды', 'error');
    return;
  }
  if (criteria.endDate <= criteria.startDate) {
    toast('Дата окончания должна быть позже даты начала', 'error');
    return;
  }

  const results = root.querySelector('#match-results');
  results.innerHTML = loadingHtml('Подбираем помещения…');
  form.querySelector('button[type="submit"]').disabled = true;

  try {
    const response = await api.premises.match(criteria);
    state.matchResult = Array.isArray(response?.candidates) ? response.candidates : [];
    state.matchCriteria = criteria;
    renderMatchResults(root, navigate);
  } catch (error) {
    if (error?.status !== 401) {
      results.innerHTML = `
        <div class="card">
          <h3 class="card-title">Подбор не выполнен</h3>
          <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
        </div>`;
    }
  } finally {
    form.querySelector('button[type="submit"]').disabled = false;
  }
}

/**
 * Отрисовать ранжированный список кандидатов.
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {Function} navigate функция навигации
 */
function renderMatchResults(root, navigate) {
  const container = root.querySelector('#match-results');
  if (!container) {
    return;
  }

  const candidates = state.matchResult || [];
  if (!candidates.length) {
    container.innerHTML = emptyHtml(
      'Подходящих помещений нет',
      'Попробуйте расширить диапазон площади, бюджета или изменить тип.',
    );
    return;
  }

  container.innerHTML = `
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Код</th>
            <th class="num">Площадь</th>
            <th class="num">С НДС, ₽/мес</th>
            <th>Соответствие</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          ${candidates
            .map((row) => {
              const score = Number(row.score) || 0;
              const scoreClass = score >= 75 ? 'is-high' : score >= 45 ? 'is-mid' : '';
              return `
            <tr>
              <td><span class="rank-badge">${escapeHtml(row.rank)}</span></td>
              <td><strong>${escapeHtml(row.code)}</strong></td>
              <td class="num">${formatArea(row.areaSqm)}</td>
              <td class="num">${formatMoney(row.estimatedMonthlyCost)}</td>
              <td>
                <span class="score-cell">
                  <span class="score-bar ${scoreClass}" style="--score: ${Math.max(
                    0,
                    Math.min(100, score),
                  )}%"><span></span></span>
                  <span class="score-value">${formatPercent(score)}</span>
                </span>
              </td>
              <td class="actions-cell">
                <button class="btn btn-primary btn-sm" type="button"
                        data-draft="${row.premisesId}">Оформить договор</button>
              </td>
            </tr>`;
            })
            .join('')}
        </tbody>
      </table>
    </div>
  `;

  container.querySelectorAll('[data-draft]').forEach((button) => {
    button.addEventListener('click', () => {
      const criteria = state.matchCriteria || {};
      const candidate = candidates.find(
        (row) => String(row.premisesId) === button.dataset.draft,
      );
      // Экран договоров реализуется в Сессии 8. Пока маршрута нет, сохраняем
      // черновик в состоянии экрана и сообщаем пользователю о выборе помещения.
      const draft = {
        premisesId: Number(button.dataset.draft),
        premisesCode: candidate?.code || '',
        startDate: criteria.startDate,
        endDate: criteria.endDate,
      };
      // Бесшовный переход в мастер договора с предзаполненным помещением и датами.
      window.location.hash = '#/contracts';
      window.dispatchEvent(
        new CustomEvent('bc:route-context', {
          detail: { route: 'contracts', context: { draft } },
        }),
      );
    });
  });
}

/**
 * Применить критерии подбора к форме (при переходе с другого экрана).
 *
 * @param {HTMLFormElement} form форма критериев
 * @param {object} criteria критерии
 */
function applyMatchCriteria(form, criteria) {
  const set = (name, value) => {
    const field = form.elements.namedItem(name);
    if (field && value !== undefined && value !== null) {
      field.value = value;
    }
  };
  set('type', criteria.type || '');
  set('targetArea', criteria.targetArea ?? '');
  set('minArea', criteria.minArea ?? '');
  set('maxArea', criteria.maxArea ?? '');
  set('maxBudget', criteria.maxBudget ?? '');
  set('startDate', criteria.startDate || '');
  set('endDate', criteria.endDate || '');
}
