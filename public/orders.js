const panel = document.getElementById('orders-panel');
const list = document.getElementById('orders-list');
const empty = document.getElementById('orders-empty');
const message = document.getElementById('orders-message');
const refresh = document.getElementById('refresh-orders');
const previous = document.getElementById('previous-page');
const next = document.getElementById('next-page');
const pagination = document.getElementById('orders-pagination');
const template = document.getElementById('order-row-template');
let currentPage = 0;
let hasMore = false;
let loading = false;

function orderState(order) {
  if (order.status === 'PAID') return { label: '已付款', tone: 'paid' };
  if (order.status === 'EXPIRED') return { label: '已过期', tone: 'expired' };
  const states = {
    PAID: { label: '已付款', tone: 'paid' },
    PROCESSING: { label: '付款处理中', tone: 'processing' },
    FAILED: { label: '付款失败', tone: 'failed' },
    UNPAID: { label: '待付款确认', tone: 'unpaid' }
  };
  return states[order.paymentStatus] || { label: '等待确认', tone: 'unpaid' };
}

function renderOrders(orders) {
  const rows = document.createDocumentFragment();
  for (const order of orders) {
    const row = template.content.cloneNode(true);
    const state = orderState(order);
    row.querySelector('.list-order-number').textContent = order.orderId;
    const time = row.querySelector('time');
    const created = new Date(order.createdAt * 1000);
    time.dateTime = created.toISOString();
    time.textContent = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
    }).format(created);
    row.querySelector('.list-product-name').textContent = order.productName;
    row.querySelector('.list-quantity').textContent = `数量 ${order.quantity} 件`;
    row.querySelector('.list-price strong').textContent = new Intl.NumberFormat('en-US', {
      style: 'currency', currency: order.currency.toUpperCase(), currencyDisplay: 'code'
    }).format(order.amount / 100);
    const pill = row.querySelector('.list-state-pill');
    pill.textContent = state.label;
    pill.dataset.state = state.tone;
    const link = row.querySelector('.order-detail-link');
    link.href = 'success.html?order_id=' + encodeURIComponent(order.orderId);
    link.setAttribute('aria-label', `查看订单 ${order.orderId} 的详情`);
    rows.append(row);
  }
  list.replaceChildren(rows);
}

async function loadOrders(page = currentPage) {
  if (loading) return;
  loading = true;
  panel.setAttribute('aria-busy', 'true');
  refresh.disabled = previous.disabled = next.disabled = true;
  message.hidden = false;
  message.classList.remove('orders-message-quiet');
  message.dataset.error = 'false';
  message.textContent = '正在读取你的订单，请稍候…';
  try {
    if (!await Account.requireUser()) return;
    // 归属由服务端认证用户确定，不从前端传递用户 ID。
    const response = await fetch('/api/orders?page=' + page, { cache: 'no-store' });
    if (response.status === 401) { Account.login(); return; }
    if (!response.ok) throw new Error('暂时无法获取订单，请稍后点击「刷新订单」重试。');
    const result = await response.json();
    renderOrders(result.orders);
    currentPage = result.page;
    hasMore = result.hasMore;
    document.getElementById('orders-count').textContent = `本页 ${result.orders.length} 笔订单`;
    document.getElementById('page-label').textContent = `第 ${currentPage + 1} 页`;
    list.hidden = result.orders.length === 0;
    empty.hidden = result.orders.length > 0;
    pagination.hidden = currentPage === 0 && !hasMore;
    message.textContent = result.orders.length ? '订单已更新。' : '当前账号暂无订单。';
    message.classList.add('orders-message-quiet');
  } catch (error) {
    list.hidden = empty.hidden = pagination.hidden = true;
    document.getElementById('orders-count').textContent = '查询未完成';
    message.classList.remove('orders-message-quiet');
    message.dataset.error = 'true';
    message.textContent = error.message;
  } finally {
    loading = false;
    panel.setAttribute('aria-busy', 'false');
    refresh.disabled = false;
    previous.disabled = currentPage === 0;
    next.disabled = !hasMore;
  }
}

refresh.addEventListener('click', () => loadOrders());
previous.addEventListener('click', () => loadOrders(currentPage - 1));
next.addEventListener('click', () => loadOrders(currentPage + 1));
loadOrders();
