/**
 * Точка входа SPA: hash-роутинг по вкладкам, проверка сессии и роли.
 *
 * Раздел 6.1 плана: экран входа, дашборд, помещения с умным подбором.
 * Модуль не содержит разметки экранов — только каркас и навигацию.
 */

import * as apiClient from './api.js';
import { getCurrentUser, isAuthenticated } from './api.js';
import { closeModalIfOpen, initModal, toast } from './ui.js';
import { renderLogin } from './views/login.js';
import { renderDashboard } from './views/dashboard.js';
import { renderPremises } from './views/premises.js';
import { renderTenants } from './views/tenants.js';
import { renderContracts } from './views/contracts.js';
import { renderPayments } from './views/payments.js';
import { renderMaintenance } from './views/maintenance.js';

/** Маршруты приложения (Сессии 7–8). */
const ROUTES = {
  dashboard: {
    title: 'Дашборд',
    render: renderDashboard,
    roles: ['ADMIN', 'MANAGER'],
  },
  premises: {
    title: 'Помещения',
    render: renderPremises,
    roles: ['ADMIN', 'MANAGER'],
  },
  tenants: {
    title: 'Арендаторы',
    render: renderTenants,
    roles: ['ADMIN', 'MANAGER'],
  },
  contracts: {
    title: 'Договоры',
    render: renderContracts,
    roles: ['ADMIN', 'MANAGER'],
  },
  payments: {
    title: 'Платежи',
    render: renderPayments,
    roles: ['ADMIN', 'MANAGER'],
  },
  maintenance: {
    title: 'Заявки на ТО',
    render: renderMaintenance,
    roles: ['ADMIN', 'MANAGER'],
  },
};

const DEFAULT_ROUTE = 'dashboard';

const loginScreen = document.getElementById('login-screen');
const appShell = document.getElementById('app-shell');
const viewRoot = document.getElementById('view-root');
const pageTitle = document.getElementById('page-title');
const userName = document.getElementById('user-name');
const userRole = document.getElementById('user-role');
const logoutBtn = document.getElementById('logout-btn');
const nav = document.getElementById('sidebar-nav');
const sidebar = document.getElementById('sidebar');
const sidebarToggle = document.getElementById('sidebar-toggle');

/** Текущий активный маршрут (нужен для защиты от гонок при быстром клике). */
let currentRoute = null;

/** Навигация по приложению. */
let navigate = () => {};

/** Показать экран входа и сбросить состояние приложения. */
function showLogin() {
  currentRoute = null;
  viewRoot.innerHTML = '';
  appShell.hidden = true;
  loginScreen.hidden = false;
  closeModalIfOpen();
  renderLogin({
    onSubmit: async (username, password) => {
      const data = await apiClient.api.login(username, password);
      apiClient.saveSession(data);
      loginScreen.hidden = true;
      bootstrapSession();
    },
  });
}

/** Показать приложение для авторизованного пользователя. */
function showApp() {
  loginScreen.hidden = true;
  appShell.hidden = false;
}

/** Заполнить шапку и меню данными пользователя. */
function renderUserChrome(user) {
  userName.textContent = user.fullName || user.username || '—';
  userRole.textContent = user.role === 'ADMIN' ? 'Управляющий' : 'Менеджер';
  userRole.dataset.role = user.role;

  nav.querySelectorAll('.nav-link[data-route]').forEach((link) => {
    const route = ROUTES[link.dataset.route];
    const allowed = route && route.roles.includes(user.role);
    link.hidden = !allowed;
  });
}

/**
 * Разобрать текущий hash в имя маршрута.
 *
 * @returns {string} имя маршрута или маршрут по умолчанию
 */
function parseHash() {
  const raw = window.location.hash.replace(/^#\/?/, '').split('?')[0].trim();
  return raw && ROUTES[raw] ? raw : DEFAULT_ROUTE;
}

/** Флаг: переход с контекстом уже отрисован, hashchange не должен перерисовывать. */
let skipNextHashRender = false;

/**
 * Перейти на маршрут с контекстом (предзаполнение формы, черновик договора).
 *
 * @param {string} name имя маршрута
 * @param {object} [context] контекст для экрана
 */
export function navigateWithContext(name, context) {
  const route = ROUTES[name] ? name : DEFAULT_ROUTE;
  const user = getCurrentUser();
  if (!user || !ROUTES[route].roles.includes(user.role)) {
    toast('Недостаточно прав для этого раздела', 'error');
    return;
  }
  const hash = `#/${route}`;
  if (window.location.hash !== hash) {
    skipNextHashRender = true;
    window.history.pushState(null, '', hash);
  }
  renderRoute(route, context);
}

/**
 * Перейти на маршрут.
 *
 * @param {string} name имя маршрута
 * @param {object} [context] контекст для экрана (например, предзаполнение)
 */
navigate = (name, context) => {
  const route = ROUTES[name] ? name : DEFAULT_ROUTE;
  const user = getCurrentUser();
  if (!user) {
    return;
  }
  if (!ROUTES[route].roles.includes(user.role)) {
    toast('Недостаточно прав для этого раздела', 'error');
    return;
  }
  const hash = `#/${route}`;
  if (window.location.hash !== hash) {
    window.history.pushState(null, '', hash);
  }
  renderRoute(route, context);
};

/**
 * Отрисовать маршрут в области контента.
 *
 * @param {string} name имя маршрута
 * @param {object} [context] контекст экрана
 */
async function renderRoute(name, context) {
  const route = ROUTES[name];
  if (!route) {
    return;
  }

  currentRoute = name;
  closeModalIfOpen();
  pageTitle.textContent = route.title;
  document.title = `${route.title} — BC-Manager`;

  nav.querySelectorAll('.nav-link').forEach((link) => {
    link.classList.toggle('active', link.dataset.route === name);
  });
  if (sidebar.classList.contains('open')) {
    sidebar.classList.remove('open');
  }

  viewRoot.innerHTML = '';
  try {
    await route.render(viewRoot, { navigate, context });
  } catch (error) {
    if (error && error.status === 401) {
      return;
    }
    viewRoot.innerHTML = `
      <div class="card">
        <h3 class="card-title">Не удалось загрузить раздел</h3>
        <p class="card-subtitle">${error?.message || 'Неизвестная ошибка'}</p>
        <button class="btn btn-secondary" type="button" data-retry>Повторить</button>
      </div>`;
    viewRoot.querySelector('[data-retry]')?.addEventListener('click', () => {
      renderRoute(name, context);
    });
  }
}

/** Поднять сессию: проверить токен, показать приложение и маршрут. */
async function bootstrapSession() {
  let user = getCurrentUser();
  try {
    const profile = await apiClient.api.me();
    user = {
      username: profile.username,
      fullName: profile.fullName,
      role: profile.role,
    };
    apiClient.saveSession({ ...user, token: apiClient.getToken() });
  } catch (error) {
    if (error && error.status === 401) {
      showLogin();
      return;
    }
    if (!user) {
      showLogin();
      return;
    }
    toast('Не удалось проверить сессию, работаем с локальными данными', 'info');
  }

  if (!user) {
    showLogin();
    return;
  }

  showApp();
  renderUserChrome(user);
  renderRoute(parseHash());
}

/** Выход из системы. */
function logout() {
  apiClient.clearSession();
  showLogin();
  toast('Вы вышли из системы', 'info');
}

window.addEventListener('hashchange', () => {
  if (skipNextHashRender) {
    skipNextHashRender = false;
    return;
  }
  if (isAuthenticated()) {
    renderRoute(parseHash());
  }
});

/** Переход с контекстом (например, из «Умного подбора» в мастер договора). */
window.addEventListener('bc:route-context', (event) => {
  const detail = event.detail || {};
  if (!detail.route || !isAuthenticated()) {
    return;
  }
  navigateWithContext(detail.route, detail.context);
});

window.addEventListener('bc:unauthorized', () => {
  showLogin();
  toast('Сессия истекла. Войдите снова.', 'error');
});

logoutBtn.addEventListener('click', logout);

sidebarToggle?.addEventListener('click', () => {
  sidebar.classList.toggle('open');
});

initModal();

if (isAuthenticated()) {
  bootstrapSession();
} else {
  showLogin();
}

export { navigate };
