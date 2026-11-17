const orderId = new URLSearchParams(window.location.search).get('order_id');
const statusElement = document.getElementById('order-status');
const detailElement = document.getElementById('order-details');
const refreshButton = document.getElementById('refresh-order');
const resultCard = document.querySelector('.result-card');
let timer;
let attempts = 0;
let loading = false;

// 页面标题与颜色始终来自实际查询结果，返回成功页本身不代表已经付款。
const states = {
  PAID: { title: '付款成功，谢谢你的选择', label: '已付款', tone: 'paid', message: '这笔订单已确认付款。你可以在下方查看订单明细。', symbol: 'M7 12l3 3 7-7' },
  PROCESSING: { title: '付款正在处理中', label: '处理中', tone: 'processing', message: '支付结果尚未最终确认，请稍候。页面会自动更新。' },
  FAILED: { title: '本次付款未成功', label: '付款失败', tone: 'failed', message: '订单尚未付款。你可以返回商品页，继续当前结账。', symbol: 'M9 9l6 6m0-6-6 6' },
  UNPAID: { title: '正在等待付款确认', label: '待确认', tone: 'unpaid', message: '暂未收到付款成功确认。如果你已完成付款，请稍候。' },
  EXPIRED: { title: '这次结账已过期', label: '已过期', tone: 'expired', message: '该订单未付款。你可以返回书店，重新开始购买。', symbol: 'M8 12h8' }
};

function displayState(state) {
  resultCard.dataset.state = state.tone;
  document.getElementById('result-title').textContent = state.title;
  document.getElementById('state-label').textContent = state.label;
  document.getElementById('status-symbol').setAttribute('d', state.symbol || 'M12 7v5l3 2');
  statusElement.textContent = state.message;
}

async function refreshOrder() {
  if (loading) return;
  clearTimeout(timer);
  if (!orderId) {
    displayState({ title: '暂时无法查看订单', label: '缺少订单号', tone: 'error', message: '请从付款后的返回链接进入此页，或返回书店继续购买。', symbol: 'M12 7v6m0 3h.01' });
    refreshButton.disabled = true;
    return;
  }
  loading = true;
  refreshButton.disabled = true;
  refreshButton.setAttribute('aria-busy', 'true');
  try {
    if (!await Account.requireUser()) return;
    const response = await fetch('/api/orders/' + encodeURIComponent(orderId), { cache: 'no-store' });
    if (response.status === 401) { Account.login(); return; }
    if (!response.ok) throw new Error(response.status === 404
      ? '订单不存在，或不属于当前登录账号。请使用下单时的账号登录。'
      : '暂时无法获取订单，请稍后点击刷新付款状态。');
    const order = await response.json();
    document.getElementById('order-product').textContent = order.productName;
    document.getElementById('order-quantity').textContent = `数量 ${order.quantity} 件`;
    document.getElementById('order-amount').textContent = new Intl.NumberFormat('en-US', {
      style: 'currency', currency: order.currency.toUpperCase(), currencyDisplay: 'code'
    }).format(order.amount / 100);
    document.getElementById('order-number').textContent = order.orderId;
    detailElement.hidden = false;
    const state = order.status === 'PAID' ? 'PAID'
      : order.status === 'EXPIRED' ? 'EXPIRED' : order.paymentStatus;
    displayState(states[state] || { title: '正在等待订单确认', label: '待确认', tone: 'unpaid', message: '订单结果暂未确认，请稍后刷新。' });
    if (state === 'PAID' || state === 'EXPIRED') {
      const continueLink = document.getElementById('continue-checkout');
      if (continueLink) continueLink.textContent = state === 'PAID' ? '返回书店' : '返回书店，重新选购';
      if (sessionStorage.getItem('checkoutOrderId') === order.orderId) {
        sessionStorage.removeItem('checkoutRequestKey');
      }
    } else if (state !== 'FAILED') {
      if (++attempts < 30) {
        timer = setTimeout(refreshOrder, 2000);
      } else {
        statusElement.textContent += ' 自动刷新已暂停，你可以稍后手动刷新。';
      }
    }
  } catch (error) {
    displayState({ title: '暂时无法确认付款结果', label: '查询未完成', tone: 'error', message: error.message, symbol: 'M12 7v6m0 3h.01' });
  } finally {
    loading = false;
    refreshButton.disabled = false;
    refreshButton.removeAttribute('aria-busy');
  }
}

refreshButton.addEventListener('click', () => {
  attempts = 0;
  refreshOrder();
});
document.getElementById('new-purchase')?.addEventListener('click', () => {
  sessionStorage.removeItem('checkoutRequestKey');
});
window.addEventListener('pagehide', () => clearTimeout(timer));
refreshOrder();
