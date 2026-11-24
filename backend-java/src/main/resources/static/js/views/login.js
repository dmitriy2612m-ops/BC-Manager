/**
 * Экран приветствия и авторизации (вход и регистрация по логину и паролю).
 *
 * Форма расположена в правом верхнем блоке стартовой страницы и поддерживает
 * переключение между режимами «Вход» и «Регистрация» (без электронной почты).
 */

const LOGIN_HINT = 'Введите логин и пароль учётной записи';
const REGISTER_HINT = 'Создаётся пустой личный аккаунт (только логин и пароль, без почты)';

/**
 * Инициализировать форму входа и регистрации на стартовой странице.
 *
 * @param {{onSubmit: (mode: 'login'|'register', username: string, password: string) => Promise<void>}} options
 *        обработчик отправки формы
 */
export function renderLogin({ onSubmit }) {
  const form = document.getElementById('login-form');
  const authCard = document.getElementById('auth-card');
  const errorBox = document.getElementById('login-error');
  const submitBtn = document.getElementById('auth-submit-btn') || form.querySelector('button[type="submit"]');
  const usernameInput = form.elements.namedItem('username');
  const passwordInput = form.elements.namedItem('password');
  const tabLogin = document.getElementById('auth-tab-login');
  const tabRegister = document.getElementById('auth-tab-register');
  const modeHint = document.getElementById('auth-mode-hint');
  const switchText = document.getElementById('auth-switch-text');
  const switchBtn = document.getElementById('auth-switch-btn');

  let mode = 'login';

  function clearError() {
    errorBox.hidden = true;
    errorBox.textContent = '';
  }

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
  }

  function setMode(nextMode, { focusInput = false } = {}) {
    mode = nextMode === 'register' ? 'register' : 'login';
    const isRegister = mode === 'register';

    clearError();
    if (tabLogin && tabRegister) {
      tabLogin.classList.toggle('active', !isRegister);
      tabLogin.setAttribute('aria-selected', String(!isRegister));
      tabRegister.classList.toggle('active', isRegister);
      tabRegister.setAttribute('aria-selected', String(isRegister));
    }

    if (passwordInput) {
      passwordInput.autocomplete = isRegister ? 'new-password' : 'current-password';
      passwordInput.placeholder = isRegister ? 'Минимум 4 символа' : '••••••••';
    }
    if (usernameInput) {
      usernameInput.placeholder = isRegister ? 'Придумайте логин (от 3 символов)' : 'Введите логин';
    }
    if (modeHint) {
      modeHint.textContent = isRegister ? REGISTER_HINT : LOGIN_HINT;
    }
    if (submitBtn) {
      submitBtn.textContent = isRegister ? 'Создать аккаунт' : 'Войти в систему';
      submitBtn.disabled = false;
    }
    if (switchText && switchBtn) {
      switchText.textContent = isRegister ? 'Уже есть аккаунт?' : 'Нет аккаунта?';
      switchBtn.textContent = isRegister ? 'Войти' : 'Зарегистрироваться';
    }

    if (focusInput) {
      authCard?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      usernameInput?.focus();
    }
  }

  if (tabLogin) {
    tabLogin.onclick = () => setMode('login', { focusInput: true });
  }
  if (tabRegister) {
    tabRegister.onclick = () => setMode('register', { focusInput: true });
  }
  if (switchBtn) {
    switchBtn.onclick = () => {
      setMode(mode === 'login' ? 'register' : 'login', { focusInput: true });
    };
  }

  form.onsubmit = async (event) => {
    event.preventDefault();

    const username = String(usernameInput?.value || '').trim();
    const password = String(passwordInput?.value || '');

    if (!username || !password) {
      showError('Введите логин и пароль');
      return;
    }

    if (mode === 'register') {
      if (username.length < 3) {
        showError('Логин должен содержать не менее 3 символов');
        usernameInput?.focus();
        return;
      }
      if (!/^[\p{L}\p{N}_.-]+$/u.test(username)) {
        showError('Логин может содержать только буквы, цифры, точки, дефисы и «_»');
        usernameInput?.focus();
        return;
      }
      if (password.length < 4) {
        showError('Пароль должен содержать не менее 4 символов');
        passwordInput?.focus();
        return;
      }
    }

    clearError();
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = mode === 'register' ? 'Регистрация…' : 'Вход…';
    }

    try {
      await onSubmit(mode, username, password);
      form.reset();
    } catch (error) {
      showError(
        error?.message
          || (mode === 'register'
            ? 'Не удалось зарегистрироваться. Попробуйте другой логин.'
            : 'Не удалось войти. Проверьте логин и пароль.'),
      );
      passwordInput?.focus();
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = mode === 'register' ? 'Создать аккаунт' : 'Войти в систему';
      }
    }
  };

  setMode('login');
}
