/**
 * Платежи и биллинг (Plan.md, раздел 6.1, п. 6).
 *
 * Реестр начислений с подсветкой просрочек, фильтрами, кнопкой пересчёта пени
 * на выбранную дату (Алгоритм 4) и модальным окном фиксации оплаты.
 *
 * Контракт API (раздел 5.5):
 *  - `GET  /payments?contractId&status&overdueOnly&asOf` — реестр;
 *  - `POST /payments/{id}/pay`                          — оплата;
 *  - `POST /payments/recalculate-penalties`             — пересчёт пени.
 */

import { api } from '../api.js';
import {
  closeModal,
  emptyHtml,
  escapeHtml,
  formatDate,
  formatMoney,
  labelOf,
  loadingHtml,
  openModal,
  statusBadge,
  toast,
  todayIso,
} from '../ui.js';

/** Состояние экрана: фильтры и последняя выдача. */
const state = {
  filters: { status: '', overdueOnly: false },
  rows: [],
  report: null,
};

/**
 * Отрисовать экран платежей.
 *
 * @param {HTMLElement} root контейнер контента
 * @param {{navigate: Function, context?: object}} options окружение
 */
export async function renderPayments(root, { context } = {}) {
  if (context?.contractId) {
    state.filters = { status: '', overdueOnly: false };
  }

  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Платежи и биллинг</h1>
        <p class="page-subtitle">График начислений, просроченная задолженность и расчёт пени</p>
        <div class="page-header-actions">
          <button class="btn btn-secondary" type="button" id="recalculate-penalties">Пересчитать пени</button>
          <button class="btn btn-ghost" type="button" id="reload-payments">Обновить</button>
        </div>
      </header>

      <div class="filters" id="payment-filters">
        <label class="field">
          <span class="field-label">Статус</span>
          <select name="status">
            <option value="">Все статусы</option>
            <option value="PENDING">Ожидает</option>
            <option value="PARTIAL">Частично</option>
            <option value="OVERDUE">Просрочен</option>
            <option value="PAID">Оплачен</option>
          </select>
        </label>
        <label class="field checkbox-field">
          <input type="checkbox" name="overdueOnly">
          <span>Только просроченные</span>
        </label>
        <button class="btn btn-secondary" type="button" id="apply-payment-filters">Применить</button>
        <button class="btn btn-ghost" type="button" id="reset-payment-filters">Сбросить</button>
      </div>

      <section id="penalty-report" hidden></section>
      <section id="payments-table">${loadingHtml()}</section>
    </div>
  `;

  root.querySelector('#reload-payments')?.addEventListener('click', () => loadTable(root));

  root.querySelector('#apply-payment-filters')?.addEventListener('click', () => {
    readFilters(root);
    loadTable(root);
  });

  root.querySelector('#reset-payment-filters')?.addEventListener('click', () => {
    state.filters = { status: '', overdueOnly: false };
    root.querySelector('#payment-filters').reset();
    loadTable(root);
  });

  root.querySelector('#recalculate-penalties')?.addEventListener('click', () => {
    openRecalculateDialog(root);
  });

  await loadTable(root);
}

/**
 * Прочитать фильтры.
 *
 * @param {HTMLElement} root корневой элемент
 */
function readFilters(root) {
  const form = root.querySelector('#payment-filters');
  state.filters = {
    status: form.elements.namedItem('status').value,
    overdueOnly: form.elements.namedItem('overdueOnly').checked,
  };
}

/**
 * Загрузить и отрисовать реестр платежей.
 *
 * @param {HTMLElement} root корневой элемент
 */
async function loadTable(root) {
  const container = root.querySelector('#payments-table');
  if (!container) {
    return;
  }
  container.innerHTML = loadingHtml();

  const query = {};
  if (state.filters.status) {
    query.status = state.filters.status;
  }
  if (state.filters.overdueOnly) {
    query.overdueOnly = true;
    query.asOf = todayIso();
  }

  let rows;
  try {
    rows = await api.payments.list(query);
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    container.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить платежи</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  state.rows = Array.isArray(rows) ? rows : [];

  if (!state.rows.length) {
    container.innerHTML = emptyHtml(
      'Платежи не найдены',
      'Измените фильтры или оформите договор — график начислений создаётся автоматически.',
    );
    return;
  }

  const totals = state.rows.reduce(
    (acc, row) => {
      acc.due += Number(row.amountDue) || 0;
      acc.paid += Number(row.paidAmount) || 0;
      acc.penalty += Number(row.penaltyAmount) || 0;
      return acc;
    },
    { due: 0, paid: 0, penalty: 0 },
  );

  container.innerHTML = `
    <div class="card summary-strip">
      <span>Начислено: <strong>${formatMoney(totals.due)}</strong></span>
      <span>Оплачено: <strong>${formatMoney(totals.paid)}</strong></span>
      <span>Пеня: <strong>${formatMoney(totals.penalty)}</strong></span>
      <span>Позиций: <strong>${state.rows.length}</strong></span>
    </div>

    <div class="table-wrap">
      <table class="data-table data-table-dense">
        <thead>
          <tr>
            <th>Договор</th>
            <th class="num">Период</th>
            <th>Срок оплаты</th>
            <th class="num">К оплате</th>
            <th class="num">Оплачено</th>
            <th class="num">Пеня</th>
            <th>Статус</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          ${state.rows
            .map(
              (row) => `
            <tr class="${isOverdue(row) ? 'row-overdue' : ''}">
              <td><strong>${escapeHtml(row.contractNumber)}</strong></td>
              <td class="num">${escapeHtml(row.periodNumber)}</td>
              <td>${formatDate(row.dueDate)}${
                row.paidDate ? `<span class="field-hint"> · оплачен ${formatDate(row.paidDate)}</span>` : ''
              }</td>
              <td class="num">${formatMoney(row.amountDue)}</td>
              <td class="num">${formatMoney(row.paidAmount)}</td>
              <td class="num">${
                Number(row.penaltyAmount) > 0
                  ? `<span class="text-danger">${formatMoney(row.penaltyAmount)}</span>`
                  : formatMoney(row.penaltyAmount)
              }</td>
              <td>${statusBadge(row.status)}</td>
              <td class="actions-cell">
                ${
                  String(row.status) !== 'PAID'
                    ? `<button class="btn btn-primary btn-sm" type="button" data-pay="${row.id}">Внести оплату</button>`
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

  container.querySelectorAll('[data-pay]').forEach((button) => {
    button.addEventListener('click', () => {
      const row = state.rows.find((item) => String(item.id) === button.dataset.pay);
      if (row) {
        openPayDialog(row, () => loadTable(root));
      }
    });
  });
}

/**
 * Просроченный ли платёж.
 *
 * @param {object} row запись платежа
 * @returns {boolean} признак просрочки
 */
function isOverdue(row) {
  return String(row.status) === 'OVERDUE';
}

/**
 * Модальное окно пересчёта пени на дату.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
function openRecalculateDialog(root) {
  openModal({
    title: 'Пересчитать пени на дату',
    bodyHtml: `
      <form id="recalc-form" class="form-grid" novalidate>
        <label class="field span-2">
          <span class="field-label">Контрольная дата расчёта *</span>
          <input type="date" name="calculationDate" required value="${todayIso()}">
        </label>
        <p class="field-hint span-2">
          Ставка 0,1 % в день за первые 30 дней просрочки и 0,2 % за каждый следующий,
          но не более 25 % от непогашенного остатка.
        </p>
        <p class="form-error span-2" role="alert" id="recalc-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">Пересчитать</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#recalc-form');
      const errorBox = body.querySelector('#recalc-error');
      const submitBtn = form.querySelector('button[type="submit"]');

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const calculationDate = form.elements.namedItem('calculationDate').value;
        if (!calculationDate) {
          errorBox.textContent = 'Укажите контрольную дату';
          errorBox.hidden = false;
          return;
        }

        submitBtn.disabled = true;
        try {
          const report = await api.payments.recalculate({ calculationDate });
          state.report = report;
          closeModal();
          renderReport(root, report);
          await loadTable(root);
          toast('Пени пересчитаны на выбранную дату', 'success');
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось пересчитать пени';
          errorBox.hidden = false;
        } finally {
          submitBtn.disabled = false;
        }
      });
    },
  });
}

/**
 * Определить номер договора по идентификатору из загруженного реестра платежей.
 *
 * @param {number|string|null|undefined} contractId идентификатор договора
 * @returns {string} номер договора или `#id`
 */
function contractLabel(contractId) {
  const match = state.rows.find((row) => String(row.contractId) === String(contractId));
  return match?.contractNumber || `#${contractId}`;
}

/**
 * Отрисовать сводку по пересчёту пени.
 *
 * @param {HTMLElement} root корневой элемент экрана
 * @param {object} report отчёт `DebtReportResponse`
 */
function renderReport(root, report) {
  const container = root.querySelector('#penalty-report');
  if (!container) {
    return;
  }

  const payments = Array.isArray(report?.payments) ? report.payments : [];
  const contracts = Array.isArray(report?.contracts) ? report.contracts : [];

  const details = payments.length
    ? `<div class="table-wrap">
         <table class="data-table data-table-dense">
           <thead>
             <tr>
               <th>Договор</th>
               <th class="num">Период</th>
               <th>Срок оплаты</th>
               <th class="num">Дней</th>
               <th class="num">Долг</th>
               <th class="num">Пеня</th>
               <th class="num">Ставка</th>
               <th>Лимит 25 %</th>
             </tr>
           </thead>
           <tbody>
             ${payments
               .map(
                 (row) => `
               <tr>
                 <td>${escapeHtml(contractLabel(row.contractId))}</td>
                 <td class="num">${escapeHtml(row.periodNumber)}</td>
                 <td>${formatDate(row.dueDate)}</td>
                 <td class="num">${escapeHtml(row.overdueDays)}</td>
                 <td class="num">${formatMoney(row.debt)}</td>
                 <td class="num">${formatMoney(row.penalty)}</td>
                 <td class="num">${escapeHtml(row.rate)} %</td>
                 <td>${row.capped ? '<span class="badge badge-warning">да</span>' : '—'}</td>
               </tr>`,
               )
               .join('')}
           </tbody>
         </table>
       </div>`
    : emptyHtml('Просрочек нет', 'На выбранную дату просроченных платежей не обнаружено.');

  container.hidden = false;
  container.innerHTML = `
    <div class="card">
      <div class="card-header-row">
        <div>
          <h3 class="card-title">Отчёт о задолженности на ${formatDate(report?.calculationDate)}</h3>
          <p class="card-subtitle">
            Итого долг: <strong>${formatMoney(report?.totalDebt)}</strong> ·
            Пеня: <strong>${formatMoney(report?.totalPenalty)}</strong> ·
            Договоров с просрочкой: <strong>${contracts.length}</strong>
          </p>
        </div>
        <button class="btn btn-ghost btn-sm" type="button" id="close-report">Скрыть</button>
      </div>
      ${details}
    </div>
  `;

  container.querySelector('#close-report')?.addEventListener('click', () => {
    container.hidden = true;
    container.innerHTML = '';
  });
}

/**
 * Модальное окно фиксации оплаты.
 *
 * @param {object} payment запись платежа
 * @param {Function} onSaved колбэк после успешной оплаты
 */
function openPayDialog(payment, onSaved) {
  const remaining = Math.max(
    0,
    Math.round(((Number(payment.amountDue) || 0) - (Number(payment.paidAmount) || 0)) * 100) / 100,
  );

  openModal({
    title: `Оплата по договору ${payment.contractNumber}`,
    bodyHtml: `
      <form id="pay-form" class="form-grid" novalidate>
        <div class="detail-list span-2">
          <div><dt>Период</dt><dd>№ ${escapeHtml(payment.periodNumber)}</dd></div>
          <div><dt>Срок оплаты</dt><dd>${formatDate(payment.dueDate)}</dd></div>
          <div><dt>К оплате</dt><dd>${formatMoney(payment.amountDue)}</dd></div>
          <div><dt>Остаток долга</dt><dd>${formatMoney(remaining)}</dd></div>
        </div>
        <label class="field">
          <span class="field-label">Сумма оплаты, ₽ *</span>
          <input type="number" name="amount" required min="0.01" step="0.01"
                 value="${remaining > 0 ? remaining : ''}">
        </label>
        <label class="field">
          <span class="field-label">Дата оплаты *</span>
          <input type="date" name="paidDate" required value="${todayIso()}">
        </label>
        <p class="field-hint span-2">
          Сумма меньше остатка зафиксирует частичную оплату (статус «Частично»).
        </p>
        <p class="form-error span-2" role="alert" id="pay-error" hidden></p>
        <div class="form-actions span-2">
          <button class="btn btn-secondary" type="button" data-cancel>Отмена</button>
          <button class="btn btn-primary" type="submit">Зафиксировать оплату</button>
        </div>
      </form>
    `,
    onMount(body) {
      const form = body.querySelector('#pay-form');
      const errorBox = body.querySelector('#pay-error');
      const submitBtn = form.querySelector('button[type="submit"]');

      body.querySelector('[data-cancel]').addEventListener('click', closeModal);

      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        errorBox.hidden = true;

        const amount = Number(form.elements.namedItem('amount').value);
        const paidDate = form.elements.namedItem('paidDate').value;
        if (!Number.isFinite(amount) || amount <= 0) {
          errorBox.textContent = 'Сумма должна быть больше нуля';
          errorBox.hidden = false;
          return;
        }
        if (!paidDate) {
          errorBox.textContent = 'Укажите дату оплаты';
          errorBox.hidden = false;
          return;
        }

        submitBtn.disabled = true;
        try {
          const updated = await api.payments.pay(payment.id, {
            amount: Math.round(amount * 100) / 100,
            paidDate,
          });
          closeModal();
          toast(
            `Оплата зафиксирована: ${labelOf(updated?.status) || 'статус обновлён'}`,
            'success',
          );
          onSaved();
        } catch (error) {
          errorBox.textContent = error?.message || 'Не удалось зафиксировать оплату';
          errorBox.hidden = false;
        } finally {
          submitBtn.disabled = false;
        }
      });
    },
  });
}
