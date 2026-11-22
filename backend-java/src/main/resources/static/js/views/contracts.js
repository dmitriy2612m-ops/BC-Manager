/**
 * Договоры аренды (Plan.md, раздел 6.1, п. 5).
 *
 * Реестр договоров со статусами, мастер оформления с живым предпросмотром
 * расчёта (Алгоритмы 1 и 3), смена статусов и печатная HTML-форма договора.
 *
 * Контракт API (раздел 5.4):
 *  - `GET   /contracts?status&tenantId&premisesId` — реестр;
 *  - `GET   /contracts/{id}`                       — карточка + график платежей;
 *  - `POST  /contracts/preview`                    — предпросмотр расчёта;
 *  - `POST  /contracts`                            — оформление;
 *  - `PATCH /contracts/{id}/status`                — смена статуса.
 */

import { api } from '../api.js';
import {
  addDaysIso,
  closeModal,
  emptyHtml,
  escapeHtml,
  formatArea,
  formatDate,
  formatDateTime,
  formatMoney,
  formatPercent,
  labelOf,
  loadingHtml,
  openModal,
  statusBadge,
  toast,
  todayIso,
} from '../ui.js';

const STATUS_OPTIONS = [
  ['', 'Все статусы'],
  ['DRAFT', 'Черновик'],
  ['ACTIVE', 'Активен'],
  ['COMPLETED', 'Завершён'],
  ['TERMINATED', 'Расторгнут'],
];

/**
 * Сдвинуть ISO-дату на один календарный год (29.02 → 28.02).
 *
 * @param {string} isoDate исходная дата `YYYY-MM-DD`
 * @returns {string} дата через год
 */
function addYearIso(isoDate) {
  const [year, month, day] = String(isoDate).split('-').map(Number);
  const target = new Date(year + 1, month - 1, 1);
  const lastDay = new Date(year + 1, month, 0).getDate();
  target.setDate(Math.min(day, lastDay));
  const mm = String(target.getMonth() + 1).padStart(2, '0');
  const dd = String(target.getDate()).padStart(2, '0');
  return `${target.getFullYear()}-${mm}-${dd}`;
}

/** Состояние экрана: фильтр по статусу и справочники для мастера. */
const state = {
  filterStatus: '',
  rows: [],
  tenants: null,
  premises: null,
};

/**
 * Отрисовать экран договоров.
 *
 * @param {HTMLElement} root контейнер контента
 * @param {{navigate: Function, context?: object}} options окружение
 */
export async function renderContracts(root, { context } = {}) {
  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Договоры аренды</h1>
        <p class="page-subtitle">Реестр договоров, оформление с предпросмотром расчёта и печатная форма</p>
        <div class="page-header-actions">
          <button class="btn btn-primary" type="button" id="new-contract">Новый договор</button>
        </div>
      </header>

      <div class="filters" id="contract-filters">
        <label class="field">
          <span class="field-label">Статус</span>
          <select name="status">${STATUS_OPTIONS.map(
            ([value, label]) =>
              `<option value="${value}" ${state.filterStatus === value ? 'selected' : ''}>${label}</option>`,
          ).join('')}</select>
        </label>
        <button class="btn btn-secondary" type="button" id="apply-contract-filters">Применить</button>
        <button class="btn btn-ghost" type="button" id="reset-contract-filters">Сбросить</button>
      </div>

      <div id="contracts-table">${loadingHtml()}</div>
    </div>
  `;

  root.querySelector('#new-contract')?.addEventListener('click', () => {
    openContractWizard(root, { context });
  });

  root.querySelector('#apply-contract-filters')?.addEventListener('click', () => {
    state.filterStatus = root.querySelector('#contract-filters select[name="status"]').value;
    loadTable(root);
  });

  root.querySelector('#reset-contract-filters')?.addEventListener('click', () => {
    state.filterStatus = '';
    root.querySelector('#contract-filters select[name="status"]').value = '';
    loadTable(root);
  });

  // Переход из других экранов: мастер с черновиком, выбор арендатора или карточка договора.
  if (context?.draft) {
    await loadDictionaries();
    openContractWizard(root, { draft: context.draft });
  } else if (context?.tenantId) {
    await loadDictionaries();
    openContractWizard(root, { tenantId: context.tenantId });
  } else if (context?.contractId) {
    await openContractCard(Number(context.contractId));
  }

  await loadTable(root);
}

/**
 * Загрузить реестр договоров.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function loadTable(root) {
  const container = root.querySelector('#contracts-table');
  if (!container) {
    return;
  }
  container.innerHTML = loadingHtml();

  const query = state.filterStatus ? { status: state.filterStatus } : {};

  let rows;
  try {
    rows = await api.contracts.list(query);
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    container.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить договоры</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  state.rows = Array.isArray(rows) ? rows : [];

  if (!state.rows.length) {
    container.innerHTML = emptyHtml(
      'Договоры не найдены',
      'Измените фильтр по статусу или оформите новый договор.',
    );
    return;
  }

  container.innerHTML = `
    <div class="table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Номер</th>
            <th>Арендатор</th>
            <th>Помещение</th>
            <th>Период</th>
            <th class="num">Плата, ₽/мес</th>
            <th>Статус</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          ${state.rows
            .map(
              (row) => `
            <tr>
              <td><strong>${escapeHtml(row.contractNumber)}</strong></td>
              <td>${escapeHtml(row.tenantName)}</td>
              <td>${escapeHtml(row.premisesCode)}</td>
              <td>${formatDate(row.startDate)} — ${formatDate(row.endDate)}</td>
              <td class="num">${formatMoney(row.monthlyRent)}</td>
              <td>${statusBadge(row.status)}</td>
              <td class="actions-cell">
                <button class="btn btn-secondary btn-sm" type="button" data-open="${row.id}">Открыть</button>
                <button class="btn btn-ghost btn-sm" type="button" data-print="${row.id}">Печать</button>
                ${statusActions(row)}
              </td>
            </tr>`,
            )
            .join('')}
        </tbody>
      </table>
    </div>
  `;

  const find = (id) => state.rows.find((item) => String(item.id) === String(id));

  container.querySelectorAll('[data-open]').forEach((button) => {
    button.addEventListener('click', () => openContractCard(Number(button.dataset.open)));
  });

  container.querySelectorAll('[data-print]').forEach((button) => {
    button.addEventListener('click', async () => {
      const row = find(button.dataset.print);
      if (row) {
        await openPrintForm(row);
      }
    });
  });

  container.querySelectorAll('[data-status]').forEach((button) => {
    button.addEventListener('click', async () => {
      const row = find(button.dataset.id);
      const status = button.dataset.status;
      if (!row) {
        return;
      }
      if (status === 'TERMINATED' && !window.confirm(`Расторгнуть договор ${row.contractNumber}?`)) {
        return;
      }
      button.disabled = true;
      try {
        await api.contracts.changeStatus(row.id, status);
        toast(`Договор ${row.contractNumber}: статус «${labelOf(status)}»`, 'success');
        await loadTable(root);
      } catch (error) {
        toast(error?.message || 'Не удалось сменить статус договора', 'error');
        button.disabled = false;
      }
    });
  });
}

/**
 * Разметка кнопок смены статуса для строки реестра.
 *
 * @param {object} row краткая карточка договора
 * @returns {string} HTML кнопок
 */
function statusActions(row) {
  if (row.status === 'DRAFT') {
    return `<button class="btn btn-ghost btn-sm" type="button" data-status="ACTIVE" data-id="${row.id}">Активировать</button>`;
  }
  if (row.status === 'ACTIVE') {
    return `
      <button class="btn btn-ghost btn-sm" type="button" data-status="COMPLETED" data-id="${row.id}">Завершить</button>
      <button class="btn btn-ghost btn-sm" type="button" data-status="TERMINATED" data-id="${row.id}">Расторгнуть</button>`;
  }
  return '';
}

/**
 * Загрузить справочники арендаторов и помещений для мастера.
 *
 * @returns {Promise<{tenants: object[], premises: object[]}>} справочники
 */
async function loadDictionaries() {
  if (state.tenants && state.premises) {
    return { tenants: state.tenants, premises: state.premises };
  }
  const [tenants, premises] = await Promise.all([
    api.tenants.list(),
    api.premises.list(),
  ]);
  state.tenants = Array.isArray(tenants) ? tenants : [];
  state.premises = Array.isArray(premises) ? premises : [];
  return { tenants: state.tenants, premises: state.premises };
}

/**
 * Открыть карточку договора с графиком платежей.
 *
 * @param {number} contractId идентификатор договора
 */
async function openContractCard(contractId) {
  openModal({
    size: 'lg',
    title: 'Договор аренды',
    bodyHtml: loadingHtml(),
    onMount(body) {
      fillContractCard(body, contractId);
    },
  });
}

/**
 * Заполнить карточку договора.
 *
 * @param {HTMLElement} body контейнер модального окна
 * @param {number} contractId идентификатор
 */
async function fillContractCard(body, contractId) {
  let details;
  try {
    details = await api.contracts.get(contractId);
  } catch (error) {
    body.innerHTML = `<p class="form-error">${escapeHtml(error?.message || 'Не удалось загрузить договор')}</p>`;
    return;
  }

  const summary = details?.summary || {};
  const payments = Array.isArray(details?.payments) ? details.payments : [];

  body.innerHTML = `
    <dl class="detail-list">
      <div><dt>Номер</dt><dd><strong>${escapeHtml(summary.contractNumber)}</strong></dd></div>
      <div><dt>Статус</dt><dd>${statusBadge(summary.status)}</dd></div>
      <div><dt>Арендатор</dt><dd>${escapeHtml(summary.tenantName)}</dd></div>
      <div><dt>Помещение</dt><dd>${escapeHtml(summary.premisesCode)}</dd></div>
      <div><dt>Период</dt><dd>${formatDate(summary.startDate)} — ${formatDate(summary.endDate)}</dd></div>
      <div><dt>Плата, ₽/мес</dt><dd>${formatMoney(summary.monthlyRent)}</dd></div>
      <div><dt>Скидка</dt><dd>${formatPercent(details.discountPercent)}</dd></div>
      <div><dt>Обеспечительный платёж</dt><dd>${formatMoney(details.depositAmount)}</dd></div>
      <div><dt>Оформил</dt><dd>${escapeHtml(details.createdBy || '—')}</dd></div>
      <div><dt>Создан</dt><dd>${formatDateTime(details.createdAt)}</dd></div>
    </dl>

    <h4 class="modal-section-title">График платежей</h4>
    ${
      payments.length
        ? `<div class="table-wrap">
             <table class="data-table data-table-dense">
               <thead>
                 <tr>
                   <th class="num">Период</th>
                   <th>Срок оплаты</th>
                   <th class="num">К оплате</th>
                   <th class="num">Оплачено</th>
                   <th class="num">Пеня</th>
                   <th>Статус</th>
                 </tr>
               </thead>
               <tbody>
                 ${payments
                   .map(
                     (row) => `
                   <tr class="${row.status === 'OVERDUE' ? 'row-overdue' : ''}">
                     <td class="num">${escapeHtml(row.periodNumber)}</td>
                     <td>${formatDate(row.dueDate)}</td>
                     <td class="num">${formatMoney(row.amountDue)}</td>
                     <td class="num">${formatMoney(row.paidAmount)}</td>
                     <td class="num">${formatMoney(row.penaltyAmount)}</td>
                     <td>${statusBadge(row.status)}</td>
                   </tr>`,
                   )
                   .join('')}
               </tbody>
             </table>
           </div>`
        : emptyHtml('График пуст', 'Платежи появятся после активации договора.')
    }
  `;
}

/**
 * Мастер оформления договора с предпросмотром.
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {{draft?: object, tenantId?: number, context?: object}} options предзаполнение
 */
async function openContractWizard(root, { draft, tenantId } = {}) {
  let tenants;
  let premises;
  try {
    ({ tenants, premises } = await loadDictionaries());
  } catch (error) {
    toast(error?.message || 'Не удалось загрузить справочники', 'error');
    return;
  }

  const activeTenants = tenants.filter((row) => row.active !== false);
  const startDate = draft?.startDate || todayIso();
  // Год аренды: с даты начала по тот же день следующего года минус один день
  // (дата окончания включительна — 12 платежей, а не 13).
  const endDate = draft?.endDate || addDaysIso(addYearIso(startDate), -1);
  const defaultNumber = `BC-${new Date().getFullYear()}-${String(
    Math.floor(Math.random() * 9000) + 1000,
  )}`;

  openModal({
    size: 'lg',
    title: 'Оформление договора аренды',
    bodyHtml: `
      <form id="contract-wizard" class="stack" novalidate>
        <div class="wizard-step">
          <h4 class="modal-section-title">1. Сторона и помещение</h4>
          <div class="form-grid">
            <label class="field span-2">
              <span class="field-label">Номер договора *</span>
              <input name="contractNumber" required maxlength="40" value="${escapeHtml(defaultNumber)}">
            </label>
            <label class="field">
              <span class="field-label">Арендатор *</span>
              <select name="tenantId" required>
                <option value="">— выберите —</option>
                ${activeTenants
                  .map(
                    (row) =>
                      `<option value="${row.id}" ${String(tenantId) === String(row.id) ? 'selected' : ''}>${escapeHtml(
                        row.companyName,
                      )}</option>`,
                  )
                  .join('')}
              </select>
            </label>
            <label class="field">
              <span class="field-label">Помещение *</span>
              <select name="premisesId" required>
                <option value="">— выберите —</option>
                ${premises
                  .filter((row) => row.status !== 'MAINTENANCE')
                  .map(
                    (row) =>
                      `<option value="${row.id}" ${String(draft?.premisesId) === String(row.id) ? 'selected' : ''}>${escapeHtml(
                        row.code,
                      )} · ${escapeHtml(labelOf(row.premisesType))} · ${formatArea(row.areaSqm)}</option>`,
                  )
                  .join('')}
              </select>
            </label>
            <label class="field">
              <span class="field-label">Дата начала *</span>
              <input type="date" name="startDate" required value="${escapeHtml(startDate)}">
            </label>
            <label class="field">
              <span class="field-label">Дата окончания *</span>
              <input type="date" name="endDate" required value="${escapeHtml(endDate)}">
            </label>
            <label class="field checkbox-field span-2">
              <input type="checkbox" name="activate" checked>
              <span>Активировать сразу (иначе договор сохранится как черновик)</span>
            </label>
          </div>
          <div class="form-actions">
            <button class="btn btn-secondary" type="button" id="preview-contract">Рассчитать</button>
          </div>
        </div>

        <div class="wizard-step">
          <h4 class="modal-section-title">2. Расчёт стоимости</h4>
          <div id="contract-preview">${emptyHtml(
            'Расчёт не выполнен',
            'Выберите помещение и даты, затем нажмите «Рассчитать».',
          )}</div>
        </div>

        <p class="form-error" role="alert" id="contract-wizard-error" hidden></p>
        <div class="form-actions">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit" id="submit-contract" disabled>Заключить договор</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#contract-wizard');
      const errorBox = body.querySelector('#contract-wizard-error');
      const previewBox = body.querySelector('#contract-preview');
      const submitBtn = form.querySelector('#submit-contract');
      const previewBtn = form.querySelector('#preview-contract');

      let lastPreview = null;

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      const readCriteria = () => ({
        premisesId: Number(form.elements.namedItem('premisesId').value) || null,
        startDate: form.elements.namedItem('startDate').value,
        endDate: form.elements.namedItem('endDate').value,
      });

      const validateDates = (criteria) => {
        if (!criteria.premisesId) {
          return 'Выберите помещение';
        }
        if (!criteria.startDate || !criteria.endDate) {
          return 'Укажите период аренды';
        }
        if (criteria.endDate <= criteria.startDate) {
          return 'Дата окончания должна быть позже даты начала';
        }
        return null;
      };

      previewBtn.addEventListener('click', async () => {
        errorBox.hidden = true;
        const criteria = readCriteria();
        const invalid = validateDates(criteria);
        if (invalid) {
          errorBox.textContent = invalid;
          errorBox.hidden = false;
          return;
        }

        previewBox.innerHTML = loadingHtml('Рассчитываем стоимость…');
        previewBtn.disabled = true;
        submitBtn.disabled = true;
        try {
          lastPreview = await api.contracts.preview(criteria);
          previewBox.innerHTML = previewMarkup(lastPreview);
          submitBtn.disabled = false;
        } catch (error) {
          lastPreview = null;
          previewBox.innerHTML = `
            <div class="alert alert-danger">
              <strong>Договор не может быть оформлен:</strong>
              <p>${escapeHtml(error?.message || 'Ошибка расчёта')}</p>
            </div>`;
        } finally {
          previewBtn.disabled = false;
        }
      });

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const criteria = readCriteria();
        const invalid = validateDates(criteria);
        if (invalid) {
          errorBox.textContent = invalid;
          errorBox.hidden = false;
          return;
        }
        const contractNumber = String(
          form.elements.namedItem('contractNumber').value || '',
        ).trim();
        const tenantValue = form.elements.namedItem('tenantId').value;
        if (!contractNumber) {
          errorBox.textContent = 'Укажите номер договора';
          errorBox.hidden = false;
          return;
        }
        if (!tenantValue) {
          errorBox.textContent = 'Выберите арендатора';
          errorBox.hidden = false;
          return;
        }

        const payload = {
          contractNumber,
          tenantId: Number(tenantValue),
          premisesId: criteria.premisesId,
          startDate: criteria.startDate,
          endDate: criteria.endDate,
          activate: form.elements.namedItem('activate').checked,
        };

        submitBtn.disabled = true;
        try {
          const saved = await api.contracts.create(payload);
          closeModal();
          toast(`Договор ${saved.contractNumber} оформлен`, 'success');
          await loadTable(root);
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось оформить договор';
          errorBox.hidden = false;
          submitBtn.disabled = false;
        }
      });
    },
  });
}

/**
 * Разметка результата предпросмотра расчёта.
 *
 * @param {object} preview `PreviewResponse`
 * @returns {string} HTML
 */
function previewMarkup(preview) {
  const schedule = Array.isArray(preview?.schedule) ? preview.schedule : [];

  return `
    <dl class="detail-list">
      <div><dt>Помещение</dt><dd>${escapeHtml(preview?.premisesCode)} · ${escapeHtml(
        labelOf(preview?.premisesType),
      )}</dd></div>
      <div><dt>Площадь</dt><dd>${formatArea(preview?.areaSqm)}</dd></div>
      <div><dt>Срок</dt><dd>${escapeHtml(preview?.months)} мес.</dd></div>
      <div><dt>Типизированная ставка</dt><dd>${formatMoney(preview?.baseMonthlyCost)}</dd></div>
      <div><dt>Эксплуатационный сбор</dt><dd>${formatMoney(preview?.opexCost)}</dd></div>
      <div><dt>Скидка за длительность</dt><dd>${formatPercent(preview?.discountPercent)}</dd></div>
      <div><dt>Сумма до НДС</dt><dd>${formatMoney(preview?.netRent)}</dd></div>
      <div><dt>НДС 20 %</dt><dd>${formatMoney(preview?.vatAmount)}</dd></div>
      <div class="is-total"><dt>Итого в месяц</dt><dd><strong>${formatMoney(
        preview?.totalMonthly,
      )}</strong></dd></div>
    </dl>

    <h4 class="modal-section-title">График платежей (${schedule.length})</h4>
    <div class="table-wrap">
      <table class="data-table data-table-dense">
        <thead>
          <tr>
            <th class="num">Период</th>
            <th>Срок оплаты</th>
            <th class="num">Сумма</th>
          </tr>
        </thead>
        <tbody>
          ${schedule
            .map(
              (row) => `
            <tr>
              <td class="num">${escapeHtml(row.periodNumber)}</td>
              <td>${formatDate(row.dueDate)}</td>
              <td class="num">${formatMoney(row.amountDue)}</td>
            </tr>`,
            )
            .join('')}
        </tbody>
      </table>
    </div>
  `;
}

/**
 * Открыть печатную HTML-форму договора в новом окне.
 *
 * @param {object} row краткая карточка договора
 */
async function openPrintForm(row) {
  let details;
  try {
    details = await api.contracts.get(row.id);
  } catch (error) {
    toast(error?.message || 'Не удалось загрузить договор для печати', 'error');
    return;
  }

  const summary = details?.summary || row;
  const payments = Array.isArray(details?.payments) ? details.payments : [];

  const printWindow = window.open('', '_blank');
  if (!printWindow) {
    toast('Браузер заблокировал окно печати. Разрешите всплывающие окна.', 'error');
    return;
  }

  printWindow.document.write(`<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <title>Договор ${escapeHtml(summary.contractNumber)}</title>
  <style>
    body { font-family: "Times New Roman", serif; font-size: 13px; line-height: 1.55; color: #111; margin: 32px; }
    h1 { font-size: 17px; text-align: center; margin: 0 0 4px; }
    .subtitle { text-align: center; margin: 0 0 24px; color: #555; font-size: 12px; }
    h2 { font-size: 14px; margin: 22px 0 8px; }
    table { width: 100%; border-collapse: collapse; margin-top: 6px; }
    th, td { border: 1px solid #999; padding: 5px 8px; font-size: 12px; }
    th { background: #eee; text-align: left; }
    .num { text-align: right; }
    dl { display: grid; grid-template-columns: 220px 1fr; gap: 4px 12px; margin: 8px 0; }
    dt { color: #555; }
    dd { margin: 0; }
    .signatures { display: flex; justify-content: space-between; gap: 40px; margin-top: 48px; }
    .signatures div { flex: 1; border-top: 1px solid #111; padding-top: 6px; font-size: 12px; }
    @media print { body { margin: 0; } }
  </style>
</head>
<body>
  <h1>ДОГОВОР АРЕНДЫ НЕЖИЛОГО ПОМЕЩЕНИЯ № ${escapeHtml(summary.contractNumber)}</h1>
  <p class="subtitle">Бизнес-центр BC-Manager · г. Москва</p>

  <h2>1. Предмет договора</h2>
  <p>
    Арендодатель предоставляет, а Арендатор принимает во временное владение и пользование
    нежилое помещение <strong>${escapeHtml(summary.premisesCode)}</strong>
    сроком с ${formatDate(summary.startDate)} по ${formatDate(summary.endDate)}.
  </p>

  <h2>2. Стороны и условия</h2>
  <dl>
    <dt>Арендатор</dt><dd>${escapeHtml(summary.tenantName)}</dd>
    <dt>Помещение</dt><dd>${escapeHtml(summary.premisesCode)}</dd>
    <dt>Срок аренды</dt><dd>${formatDate(summary.startDate)} — ${formatDate(summary.endDate)}</dd>
    <dt>Ежемесячная плата</dt><dd>${formatMoney(summary.monthlyRent)} (включая НДС 20 %)</dd>
    <dt>Скидка за длительность</dt><dd>${formatPercent(details.discountPercent)}</dd>
    <dt>Обеспечительный платёж</dt><dd>${formatMoney(details.depositAmount)}</dd>
    <dt>Статус договора</dt><dd>${escapeHtml(labelOf(summary.status))}</dd>
  </dl>

  <h2>3. График платежей</h2>
  ${
    payments.length
      ? `<table>
           <thead>
             <tr><th class="num">Период</th><th>Срок оплаты</th><th class="num">Сумма к оплате</th></tr>
           </thead>
           <tbody>
             ${payments
               .map(
                 (row) => `<tr><td class="num">${escapeHtml(row.periodNumber)}</td><td>${formatDate(
                   row.dueDate,
                 )}</td><td class="num">${formatMoney(row.amountDue)}</td></tr>`,
               )
               .join('')}
           </tbody>
         </table>`
      : '<p>График платежей ещё не сформирован.</p>'
  }

  <h2>4. Подписи сторон</h2>
  <div class="signatures">
    <div>Арендодатель<br>_______________________</div>
    <div>Арендатор: ${escapeHtml(summary.tenantName)}<br>_______________________</div>
  </div>

  <script>window.addEventListener('load', function () { window.print(); });</script>
</body>
</html>`);
  printWindow.document.close();
}
