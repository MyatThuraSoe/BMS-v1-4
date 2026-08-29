// Barrel re-export — public API surface unchanged.
// Pages keep importing exactly as before:
//   import { saleService } from '../api/services';
// Domain modules live in ./services/*.js for long-term maintainability.
export * from './services/auth';
export * from './services/license';
export * from './services/users';
export * from './services/system';
export * from './services/audit';
export * from './services/catalog';
export * from './services/orders';
export * from './services/shifts';
export * from './services/procurement';
export * from './services/customers';
export * from './services/sales';
export * from './services/receipts';
export * from './services/inventory';
export * from './services/expenses';
export * from './services/reports';
export * from './services/backup';
