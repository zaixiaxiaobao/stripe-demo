const form = document.getElementById('account-form');
const submit = document.getElementById('account-submit');
const message = document.getElementById('account-message');
const loginTab = document.getElementById('login-tab');
const registerTab = document.getElementById('register-tab');
const passwordInput = document.getElementById('password');
const confirmation = document.getElementById('confirm-password');
let registerMode = false;

// 只允许回到本商店页面，防止登录后被外部地址带走。
function destination() {
  const requested = new URLSearchParams(location.search).get('returnTo') || '/orders.html';
  try {
    const url = new URL(requested, location.origin);
    if (url.origin === location.origin && /^\/(checkout|orders|success|cancel)\.html$/.test(url.pathname)) {
      return url.pathname + url.search;
    }
  } catch { }
  return '/orders.html';
}

function switchMode(register) {
  registerMode = register;
  loginTab.setAttribute('aria-pressed', String(!register));
  registerTab.setAttribute('aria-pressed', String(register));
  document.getElementById('account-title').textContent = register ? '在纸间，留一个位置' : '欢迎回到纸间';
  document.getElementById('confirm-field').hidden = !register;
  document.getElementById('password-note').hidden = !register;
  confirmation.required = register;
  passwordInput.autocomplete = register ? 'new-password' : 'current-password';
  passwordInput.minLength = register ? 8 : 1;
  submit.textContent = register ? '注册并登录' : '登录';
  message.textContent = '';
}
loginTab.addEventListener('click', () => switchMode(false));
registerTab.addEventListener('click', () => switchMode(true));

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  submit.disabled = loginTab.disabled = registerTab.disabled = true;
  message.dataset.error = 'false';
  message.textContent = registerMode ? '正在创建账号…' : '正在登录…';
  const values = { email: document.getElementById('email').value.trim(), password: passwordInput.value };
  try {
    if (registerMode) {
      if (values.password !== confirmation.value) throw new Error('两次输入的密码不一致。');
      const registration = await Account.post('/api/register', values);
      if (!registration.ok) {
        const result = await registration.json().catch(() => ({}));
        throw new Error(result.error || '注册失败，请刷新后重试。');
      }
    }
    const response = await Account.post('/api/login', values);
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      throw new Error(result.error || '登录失败，请刷新后重试。');
    }
    Account.clearPurchase();
    await Account.current();
    window.location.replace(destination());
  } catch (error) {
    message.dataset.error = 'true';
    message.textContent = error.message;
    submit.disabled = loginTab.disabled = registerTab.disabled = false;
  }
});
Account.ready.then(user => { if (user) window.location.replace(destination()); });
