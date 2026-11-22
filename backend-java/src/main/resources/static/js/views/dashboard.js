/**
 * Дашборд: KPI-карточки, графики Chart.js, истекающие договоры и CSV-экспорт
 * (Plan.md, раздел 6.1, п. 2).
 *
 * Данные берутся из `GET /api/v1/analytics/summary` (Алгоритм 6). Графики
 * пересоздаются при каждом входе на экран, чтобы не накапливать экземпляры
 * Chart.js в памяти.
 */

import { api } from '../api.js';
import {
  emptyHtml,
  escapeHtml,
  formatArea,
  formatDate,
  formatMoney,
  formatPercent,
  loadingHtml,
  toast,
} from '../ui.js';

/** Ссылки на активные графики, чтобы уничтожать их перед перерисовкой. */
const charts = [];

/** Уничтожить ранее созданные графики. */
function destroyCharts() {
  while (charts.length) {
    const chart = charts.pop();
    try {
      chart.destroy();
    } catch {
      // Игнорируем: график мог быть уже удалён вместе с контейнером.
    }
  }
}

/**
 * Отрисовать дашборд.
 *
 * @param {HTMLElement} root контейнер контента
 */
export async function renderDashboard(root) {
  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <h1>Дашборд</h1>
        <p class="page-subtitle">Сводные показатели бизнес-центра на текущую дату</p>
        <div class="page-header-actions">
          <button class="btn btn-secondary" type="button" id="reload-dashboard">Обновить</button>
          <button class="btn btn-primary" type="button" id="export-csv">Экспорт в CSV</button>
        </div>
      </header>
      <section id="dashboard-body">${loadingHtml()}</section>
    </div>
  `;

  root.querySelector('#reload-dashboard')?.addEventListener('click', () => loadDashboard(root));
  root.querySelector('#export-csv')?.addEventListener('click', async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
      await api.analytics.exportContractsCsv();
      toast('Файл contracts.csv сформирован', 'success');
    } catch (error) {
      toast(error?.message || 'Не удалось выгрузить CSV', 'error');
    } finally {
      button.disabled = false;
    }
  });

  await loadDashboard(root);
}

/**
 * Загрузить и отрисовать KPI.
 *
 * @param {HTMLElement} root корневой элемент экрана
 */
async function loadDashboard(root) {
  const body = root.querySelector('#dashboard-body');
  if (!body) {
    return;
  }
  body.innerHTML = loadingHtml();

  let summary;
  try {
    summary = await api.analytics.summary();
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
    body.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить аналитику</h3>
        <p class="card-subtitle">${escapeHtml(error?.message || 'Неизвестная ошибка')}</p>
      </div>`;
    return;
  }

  body.innerHTML = dashboardMarkup(summary);
  initCharts(body, summary);
}

/**
 * Разметка KPI-блоков и таблиц.
 *
 * @param {object} s сводка аналитики
 * @returns {string} HTML
 */
function dashboardMarkup(s) {
  const expiring = Array.isArray(s.expiringContracts) ? s.expiringContracts : [];
  const byType = Array.isArray(s.byType) ? s.byType : [];
  const byFloor = Array.isArray(s.byFloor) ? s.byFloor : [];

  const overdueCardClass = Number(s.overduePaymentsCount) > 0 ? 'is-danger' : '';
  const criticalCardClass = Number(s.openCriticalRequests) > 0 ? 'is-warning' : '';

  const expiringList = expiring.length
    ? `<ul class="expiring-list">
        ${expiring
          .map(
            (row) => `
          <li>
            <span>
              <strong>${escapeHtml(row.contractNumber)}</strong>
              <span class="field-hint"> · до ${formatDate(row.endDate)}</span>
            </span>
            <span class="days-left">${row.daysLeft} дн.</span>
          </li>`,
          )
          .join('')}
      </ul>`
    : emptyHtml('Истекающих договоров нет', 'В ближайшие 30 дней договоры не заканчиваются.');

  return `
    <div class="stack">
      <div class="kpi-grid">
        <article class="kpi-card is-success">
          <p class="kpi-label">Загруженность БЦ</p>
          <p class="kpi-value">${formatPercent(s.occupancyPercent)}</p>
          <p class="kpi-foot">${formatArea(s.occupiedAreaSqm)} из ${formatArea(s.totalAreaSqm)}</p>
        </article>
        <article class="kpi-card">
          <p class="kpi-label">Ежемесячная выручка (MRR)</p>
          <p class="kpi-value">${formatMoney(s.mrr)}</p>
          <p class="kpi-foot">Доходность: ${formatMoney(s.averageYieldPerSqm)} / м²</p>
        </article>
        <article class="kpi-card ${overdueCardClass}">
          <p class="kpi-label">Просроченная задолженность</p>
          <p class="kpi-value">${formatMoney(s.overdueAmount)}</p>
          <p class="kpi-foot">Платежей с просрочкой: ${Number(s.overduePaymentsCount) || 0}</p>
        </article>
        <article class="kpi-card ${criticalCardClass}">
          <p class="kpi-label">Открытые заявки на ТО</p>
          <p class="kpi-value">${Number(s.openCriticalRequests) || 0}</p>
          <p class="kpi-foot">Критических заявок в работе</p>
        </article>
      </div>

      <div class="grid-2">
        <section class="card">
          <h3 class="card-title">Загруженность по типам помещений</h3>
          <p class="card-subtitle">Доля занятой площади, %</p>
          <div class="chart-box">
            ${
              byType.length
                ? '<canvas id="chart-by-type" aria-label="Загруженность по типам"></canvas>'
                : emptyHtml('Нет данных')
            }
          </div>
        </section>
        <section class="card">
          <h3 class="card-title">Загруженность по этажам</h3>
          <p class="card-subtitle">Занятая площадь, м²</p>
          <div class="chart-box">
            ${
              byFloor.length
                ? '<canvas id="chart-by-floor" aria-label="Загруженность по этажам"></canvas>'
                : emptyHtml('Нет данных')
            }
          </div>
        </section>
      </div>

      <section class="card">
        <h3 class="card-title">Истекающие договоры (30 дней)</h3>
        <p class="card-subtitle">Договоры, требующие продления или переговоров</p>
        ${expiringList}
      </section>
    </div>
  `;
}

/**
 * Инициализировать графики Chart.js.
 *
 * @param {HTMLElement} body контейнер дашборда
 * @param {object} summary сводка аналитики
 */
function initCharts(body, summary) {
  destroyCharts();

  const ChartCtor = window.Chart;
  if (typeof ChartCtor !== 'function') {
    // Chart.js мог не загрузиться (нет сети) — KPI и таблицы остаются рабочими.
    return;
  }

  const accent = '#1e3a5f';
  const accentSoft = 'rgba(30, 58, 95, 0.75)';

  const typeCanvas = body.querySelector('#chart-by-type');
  if (typeCanvas) {
    const rows = summary.byType || [];
    const chart = new ChartCtor(typeCanvas, {
      type: 'doughnut',
      data: {
        labels: rows.map((row) => labelOfType(row.type)),
        datasets: [
          {
            label: 'Загруженность, %',
            data: rows.map((row) => Number(row.occupancyPercent) || 0),
            backgroundColor: ['#1e3a5f', '#3d6d9e', '#7aa3cc', '#b9cfe4'],
            borderWidth: 0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.label}: ${formatPercent(ctx.raw)}`,
            },
          },
        },
      },
    });
    charts.push(chart);
  }

  const floorCanvas = body.querySelector('#chart-by-floor');
  if (floorCanvas) {
    const rows = summary.byFloor || [];
    const chart = new ChartCtor(floorCanvas, {
      type: 'bar',
      data: {
        labels: rows.map((row) => `${row.floor} эт.`),
        datasets: [
          {
            label: 'Занятая площадь, м²',
            data: rows.map((row) => Number(row.occupiedAreaSqm) || 0),
            backgroundColor: accent,
            borderRadius: 4,
          },
          {
            label: 'Общая площадь, м²',
            data: rows.map((row) => Number(row.totalAreaSqm) || 0),
            backgroundColor: accentSoft,
            borderRadius: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { position: 'bottom' },
          tooltip: {
            callbacks: {
              label: (ctx) => `${ctx.dataset.label}: ${formatArea(ctx.raw)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: { callback: (value) => `${value} м²` },
          },
        },
      },
    });
    charts.push(chart);
  }
}

/**
 * Подпись типа помещения для графика.
 *
 * @param {string} type код типа
 * @returns {string} подпись
 */
function labelOfType(type) {
  const map = {
    OFFICE: 'Офисы',
    RETAIL: 'Торговые',
    CONFERENCE: 'Переговорные',
    PARKING: 'Паркинг',
  };
  return map[type] || type || '—';
}
