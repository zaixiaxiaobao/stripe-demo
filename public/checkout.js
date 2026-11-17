const checkoutForm = document.querySelector('form');
const checkoutButton = document.getElementById('checkout-button');
const checkoutMessage = document.getElementById('checkout-message');

checkoutForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  checkoutButton.disabled = true;
  checkoutButton.setAttribute('aria-busy', 'true');
  checkoutMessage.dataset.error = 'false';
  checkoutMessage.textContent = '正在创建收银台…';
  try {
    if (!await Account.requireUser()) return;
    let requestKey = sessionStorage.getItem('checkoutRequestKey');
    if (!requestKey) {
      requestKey = crypto.randomUUID();
      sessionStorage.setItem('checkoutRequestKey', requestKey);
    }
    const response = await Account.post('/create-checkout-session', { requestKey });
    if (response.status === 401) { Account.login(); return; }
    if (!response.ok) {
      throw new Error(response.status === 409
        ? '当前结账已不可用，请重新打开商品页后开始新购买。'
        : '创建失败，请重试；重试会沿用同一请求标识。');
    }
    const result = await response.json();
    sessionStorage.setItem('checkoutOrderId', result.orderId);
    if (result.status === 'EXPIRED') {
      sessionStorage.removeItem('checkoutRequestKey');
      throw new Error('收银台已过期，再次点击可开始新购买。');
    }
    if (!result.url) throw new Error('收银台暂不可用，请稍后重试。');
    window.location.assign(result.url);
  } catch (error) {
    checkoutMessage.dataset.error = 'true';
    checkoutMessage.textContent = error.message;
    checkoutButton.disabled = false;
    checkoutButton.removeAttribute('aria-busy');
  }
});
