/**
 * Warms the browser's module cache with every route chunk while the app sits
 * idle after login. Total payload of all pages is ~500 KB over localhost —
 * trivially cheap — and it guarantees the FIRST click on any menu renders
 * instantly instead of flashing the Suspense fallback.
 *
 * Import paths are static literals so Vite bundles these into the same
 * per-page chunks React.lazy already uses (module cache dedupes the work).
 */
const pageImporters = [
  () => import('../pages/Dashboard'),
  () => import('../pages/POS'),
  () => import('../pages/Products'),
  () => import('../pages/ProductForm'),
  () => import('../pages/ProductDetail'),
  () => import('../pages/Categories'),
  () => import('../pages/CategoryForm'),
  () => import('../pages/Suppliers'),
  () => import('../pages/SupplierForm'),
  () => import('../pages/SupplierDetails'),
  () => import('../pages/Purchases'),
  () => import('../pages/PurchaseForm'),
  () => import('../pages/Customers'),
  () => import('../pages/CustomerForm'),
  () => import('../pages/CustomerDetails'),
  () => import('../pages/Sales'),
  () => import('../pages/SaleDetail'),
  () => import('../pages/ReceiptPreview'),
  () => import('../pages/Orders'),
  () => import('../pages/Inventory'),
  () => import('../pages/StockAdjustment'),
  () => import('../pages/Reports'),
  () => import('../pages/Analytics'),
  () => import('../pages/Accounting'),
  () => import('../pages/AccountsReceivable'),
  () => import('../pages/Users'),
  () => import('../pages/UserForm'),
  () => import('../pages/UserUpdate'),
  () => import('../pages/Settings'),
  () => import('../pages/BackupSettings'),
  () => import('../pages/ShopInfo'),
  () => import('../pages/ReceiptCustomization'),
  () => import('../pages/AuditLogs'),
  () => import('../pages/CashShift'),
  () => import('../pages/ShiftHistory'),
  () => import('../pages/About'),
];

let started = false;

export function preloadRouteChunks() {
  if (started) return; // once per session
  started = true;

  const run = () => {
    // Fire sequentially-ish: idle callbacks keep the main thread free
    let i = 0;
    function schedule(cb) {
      if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback(cb, { timeout: 4000 });
      } else {
        setTimeout(() => cb({ timeRemaining: () => 50 }), 300);
      }
    }
    const next = (deadline) => {
      while (i < pageImporters.length && deadline.timeRemaining() > 5) {
        pageImporters[i]().catch(() => {});
        i++;
      }
      if (i < pageImporters.length) {
        schedule(next);
      }
    };
    schedule(next);
  };

  // Start after the shell has painted, never blocking first render
  if (typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(run, { timeout: 3000 });
  } else {
    setTimeout(run, 1200);
  }
}
