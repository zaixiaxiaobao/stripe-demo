const orderId = new URLSearchParams(window.location.search).get('order_id');
const statusElement = document.getElementById('order-status');
const detailElement = document.getElementById('order-details');
let timer;
let attempts = 0;

async function refreshOrder() {
  clearTimeout(timer);
  if (!orderId) {
    statusElement.textContent = '缺少订单号，无法确认付款结果。';
    return;
  }
  try {
    const response = await fetch('/api/orders/' + encodeURIComponent(orderId), { cache: 'no-store' });
    if (!response.ok) throw new Error(response.status === 404
      ? '订单不存在，或当前浏览器会话无权查询。' : '查询失败，请稍后刷新。');
    const order = await response.json();
    detailElement.textContent = `${order.productName} × ${order.quantity} — ${order.currency.toUpperCase()} ${(order.amount / 100).toFixed(2)} — 订单 ${order.orderId}`;
    const messages = {
      PAID: '付款成功。',
      PROCESSING: '付款处理中，请等待最终结果。',
      FAILED: '本次付款失败，订单尚未付款。',
      UNPAID: '尚未确认付款，正在等待支付结果。',
      EXPIRED: '收银台已过期，订单未付款。'
    };
    const state = order.status === 'EXPIRED' ? 'EXPIRED' : order.paymentStatus;
    statusElement.textContent = messages[state] || '等待确认付款结果。';
    if (state === 'PAID' || state === 'EXPIRED') {
      if (sessionStorage.getItem('checkoutOrderId') === order.orderId) {
        sessionStorage.removeItem('checkoutRequestKey');
      }
    } else if (state !== 'FAILED' && ++attempts < 30) {
      timer = setTimeout(refreshOrder, 2000);
    }
  } catch (error) {
    statusElement.textContent = error.message;
  }
}

document.getElementById('refresh-order').addEventListener('click', () => {
  attempts = 0;
  refreshOrder();
});
document.getElementById('new-purchase')?.addEventListener('click', () => {
  sessionStorage.removeItem('checkoutRequestKey');
});
refreshOrder();
