/**
 * Личный кабинет пользователя: редактирование ФИО и смена пароля.
 */

import { api, getCurrentUser, saveSession } from '../api.js';
import { escapeHtml, labelOf, toast } from '../ui.js';

/**
 * Отрисовать экран личного кабинета.
 *
 * @param {HTMLElement} root контейнер контента
 */
export async function renderProfile(root) {
  let user = getCurrentUser() || {};
  try {
    const fresh = await api.me();
    if (fresh) {
      user = fresh;
    }
  } catch (error) {
    if (error?.status === 401) {
      return;
    }
  }

  const workspaceStatus = user.role === 'ADMIN'
    ? 'Персональный рабочий кабинет (полный доступ к управлению бизнес-центром)'
    : 'Рабочий кабинет менеджера (операционная работа с договорами и платежами)';

  root.innerHTML = `
    <div class="stack">
      <header class="page-header">
        <div>
          <h1>Личный кабинет</h1>
          <p class="page-subtitle">Управление личными данными и паролем учётной записи</p>
        </div>
      </header>

      <div class="grid-2">
        <section class="card">
          <h3 class="card-title">Данные учётной записи</h3>
          <p class="card-subtitle">Вы можете обновить ФИО, отображаемое в шапке и документах</p>

          <dl class="detail-list" style="margin-bottom: 18px;">
            <div>
              <dt>Логин</dt>
              <dd><strong>${escapeHtml(user.username || '—')}</strong></dd>
            </div>
            <div>
              <dt>Текущее ФИО</dt>
              <dd id="profile-fullname-display">${escapeHtml(user.fullName || user.username || '—')}</dd>
            </div>
            <div>
              <dt>Роль в системе</dt>
              <dd>${escapeHtml(labelOf(user.role))}</dd>
            </div>
            <div>
              <dt>Режим доступа</dt>
              <dd>${escapeHtml(workspaceStatus)}</dd>
            </div>
          </dl>

          <form id="update-profile-form" class="stack" action="/api/v1/auth/profile" method="post" novalidate>
            <label class="field" for="profile-fullname">
              <span class="field-label">ФИО</span>
              <input type="text" id="profile-fullname" name="fullName"
                     autocomplete="name" minlength="2" maxlength="120"
                     value="${escapeHtml(user.fullName || '')}"
                     placeholder="Введите фамилию, имя и отчество" required>
            </label>

            <p id="update-profile-error" class="form-error" role="alert" hidden></p>

            <div class="form-actions">
              <button type="submit" id="update-profile-submit" class="btn btn-secondary">
                Сохранить ФИО
              </button>
            </div>
          </form>
        </section>

        <section class="card">
          <h3 class="card-title">Смена пароля</h3>
          <p class="card-subtitle">После обновления пароля текущая сессия остаётся активной, а старый пароль перестаёт действовать</p>

          <form id="change-password-form" class="stack" action="/api/v1/auth/change-password" method="post" novalidate>
            <label class="field" for="current-password">
              <span class="field-label">Текущий пароль</span>
              <input type="password" id="current-password" name="currentPassword"
                     autocomplete="current-password" placeholder="Введите текущий пароль" required>
            </label>

            <label class="field" for="new-password">
              <span class="field-label">Новый пароль</span>
              <input type="password" id="new-password" name="newPassword"
                     autocomplete="new-password" minlength="4" maxlength="100"
                     placeholder="Не менее 4 символов" required>
            </label>

            <label class="field" for="confirm-password">
              <span class="field-label">Подтвердите новый пароль</span>
              <input type="password" id="confirm-password" name="confirmPassword"
                     autocomplete="new-password" minlength="4" maxlength="100"
                     placeholder="Повторите новый пароль" required>
            </label>

            <p id="change-password-error" class="form-error" role="alert" hidden></p>

            <div class="form-actions">
              <button type="submit" id="change-password-submit" class="btn btn-primary">
                Сменить пароль
              </button>
            </div>
          </form>
        </section>
      </div>
    </div>
  `;

  const profileForm = root.querySelector('#update-profile-form');
  const profileErrorBox = root.querySelector('#update-profile-error');
  const profileSubmitBtn = root.querySelector('#update-profile-submit');
  const fullNameDisplay = root.querySelector('#profile-fullname-display');

  const showProfileError = (message) => {
    profileErrorBox.textContent = message;
    profileErrorBox.hidden = false;
  };

  const clearProfileError = () => {
    profileErrorBox.textContent = '';
    profileErrorBox.hidden = true;
  };

  profileForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearProfileError();

    const fullName = String(profileForm.elements.namedItem('fullName')?.value || '').trim();
    if (fullName.length < 2) {
      showProfileError('ФИО должно содержать не менее 2 символов');
      profileForm.elements.namedItem('fullName')?.focus();
      return;
    }
    if (fullName.length > 120) {
      showProfileError('ФИО не должно превышать 120 символов');
      profileForm.elements.namedItem('fullName')?.focus();
      return;
    }

    profileSubmitBtn.disabled = true;
    profileSubmitBtn.textContent = 'Сохранение…';

    try {
      const data = await api.updateProfile(fullName);
      saveSession(data);
      if (fullNameDisplay) {
        fullNameDisplay.textContent = data.fullName || fullName;
      }
      window.dispatchEvent(new CustomEvent('bc:user-updated', { detail: data }));
      toast('ФИО обновлено', 'success');
    } catch (error) {
      showProfileError(error?.message || 'Не удалось обновить ФИО');
    } finally {
      profileSubmitBtn.disabled = false;
      profileSubmitBtn.textContent = 'Сохранить ФИО';
    }
  });

  const form = root.querySelector('#change-password-form');
  const errorBox = root.querySelector('#change-password-error');
  const submitBtn = root.querySelector('#change-password-submit');

  const showError = (message) => {
    errorBox.textContent = message;
    errorBox.hidden = false;
  };

  const clearError = () => {
    errorBox.textContent = '';
    errorBox.hidden = true;
  };

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearError();

    const currentPassword = String(form.elements.namedItem('currentPassword')?.value || '');
    const newPassword = String(form.elements.namedItem('newPassword')?.value || '');
    const confirmPassword = String(form.elements.namedItem('confirmPassword')?.value || '');

    if (!currentPassword || !newPassword || !confirmPassword) {
      showError('Заполните все поля формы смены пароля');
      return;
    }

    if (newPassword.length < 4) {
      showError('Новый пароль должен содержать не менее 4 символов');
      form.elements.namedItem('newPassword')?.focus();
      return;
    }

    if (newPassword !== confirmPassword) {
      showError('Новый пароль и подтверждение не совпадают');
      form.elements.namedItem('confirmPassword')?.focus();
      return;
    }

    if (newPassword === currentPassword) {
      showError('Новый пароль должен отличаться от текущего');
      form.elements.namedItem('newPassword')?.focus();
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = 'Сохранение…';

    try {
      const data = await api.changePassword(currentPassword, newPassword);
      saveSession(data);
      form.reset();
      toast('Пароль успешно изменён', 'success');
    } catch (error) {
      showError(error?.message || 'Не удалось изменить пароль');
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Сменить пароль';
    }
  });
}
