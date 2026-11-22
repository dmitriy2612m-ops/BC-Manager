/**
 * Экран входа в систему (Plan.md, раздел 6.1).
 *
 * Форма минималистична: логин и пароль, ошибки выводятся под формой,
 * кнопка блокируется на время запроса. JWT сохраняется в `api.js`.
 */

/**
 * Отрисовать форму входа.
 *
 * @param {{onSubmit: (username: string, password: string) => Promise<void>}} options
 *        обработчик успешного входа
 */
export function renderLogin({ onSubmit }) {
  const form = document.getElementById('login-form');
  const errorBox = document.getElementById('login-error');
  const submitBtn = form.querySelector('button[type="submit"]');
  const usernameInput = form.elements.namedItem('username');
  const passwordInput = form.elements.namedItem('password');

  errorBox.hidden = true;
  errorBox.textContent = '';
  submitBtn.disabled = false;

  form.onsubmit = async (event) => {
    event.preventDefault();

    const username = String(usernameInput?.value || '').trim();
    const password = String(passwordInput?.value || '');

    if (!username || !password) {
      showError('Введите логин и пароль');
      return;
    }

    errorBox.hidden = true;
    submitBtn.disabled = true;
    submitBtn.textContent = 'Вход…';

    try {
      await onSubmit(username, password);
      form.reset();
    } catch (error) {
      showError(error?.message || 'Не удалось войти. Проверьте логин и пароль.');
      passwordInput?.focus();
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = 'Войти';
    }
  };

  usernameInput?.focus();

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
  }
}
