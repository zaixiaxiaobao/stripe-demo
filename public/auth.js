// 各页面共用登录状态、CSRF 请求与账号切换处理。
window.Account = (() => {
  function clearPurchase() {
    sessionStorage.removeItem('checkoutRequestKey');
    sessionStorage.removeItem('checkoutOrderId');
    sessionStorage.removeItem('checkoutUserId');
  }

  function login() {
    const returnTo = window.location.pathname + window.location.search;
    window.location.assign('/login.html?returnTo=' + encodeURIComponent(returnTo));
  }

  async function post(path, values = {}) {
    const csrfResponse = await fetch('/api/csrf', { cache: 'no-store' });
    if (!csrfResponse.ok) throw new Error('无法验证请求，请刷新后重试。');
    const csrf = await csrfResponse.json();
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
      body: new URLSearchParams(values)
    });
  }

  function render(user) {
    const menu = document.getElementById('account-menu');
    if (!menu) return;
    menu.replaceChildren();
    if (!user) {
      const link = document.createElement('a');
      link.href = '/login.html?returnTo=' + encodeURIComponent(window.location.pathname + window.location.search);
      link.textContent = '登录 / 注册';
      menu.append(link);
      return;
    }
    const email = document.createElement('span');
    email.className = 'account-email';
    email.textContent = user.email;
    email.title = user.email;
    const logout = document.createElement('button');
    logout.type = 'button';
    logout.className = 'logout-button';
    logout.textContent = '退出';
    logout.addEventListener('click', async () => {
      logout.disabled = true;
      try {
        const response = await post('/api/logout');
        if (!response.ok) throw new Error('退出失败，请重试。');
        clearPurchase();
        window.location.assign('/login.html');
      } catch (error) {
        logout.disabled = false;
        logout.textContent = '重试退出';
        logout.title = error.message;
      }
    });
    menu.append(email, logout);
  }

  async function current() {
    const response = await fetch('/api/me', { cache: 'no-store' });
    if (response.status === 401) { render(null); return null; }
    if (!response.ok) throw new Error('暂时无法获取登录状态，请刷新后重试。');
    const user = await response.json();
    if (sessionStorage.getItem('checkoutUserId') !== user.id) clearPurchase();
    sessionStorage.setItem('checkoutUserId', user.id);
    render(user);
    return user;
  }

  const ready = current().catch(() => { render(null); return null; });
  async function requireUser() {
    // 每次访问时确认服务端身份，避免多标签页切换账号后使用过期的前端状态。
    const user = await current();
    if (!user) login();
    return user;
  }
  return { ready, post, current, requireUser, login, clearPurchase };
})();
