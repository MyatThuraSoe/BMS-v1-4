# TODO - Requirements Doc 1 (Foundation & UX Improvements)

## Phase 1: Shop Information + Receipt branding
- [x] Add backend data model: `shop_info` table migration in `bms_schema.sql`
- [x] Implement backend entity + repository + DTO + service: `ShopInfo`
- [x] Implement backend endpoints:
  - [x] GET `/api/shop-info` (defaults if none)
  - [x] PUT `/api/shop-info` (admin upsert)
  - [x] POST `/api/shop-info/logo` (multipart upload)
  - [x] GET `/api/shop-info/logo` (returns bytes)
  - [x] DELETE `/api/shop-info/logo` (admin)
- [x] Update receipt generation (HTML/PDF/PNG): replace hardcoded “BMS v1” with ShopInfo name/address/phone and embed logo conditionally
- [x] Update POS receipt dialog preview to use ShopInfo name/logo
- [x] Frontend: implement `ShopInfo.jsx` (Admin only)
- [x] Frontend: implement `ShopLogo.jsx` component
- [x] Frontend: extend `frontend/src/api/services.js` with `shopInfoService`
- [ ] Frontend: add sidebar menu item for shop info under Settings (Admin only)

## Phase 2: Product category filtering
- [ ] Backend: add optional `categoryId` to GET `/api/products` and wire to service/repository
- [x] Frontend: add category dropdown filter to `Products.jsx` and refetch with `queryKey` + pass `categoryId`
- [x] Frontend: add category chips/tabs to `POS.jsx` and filter client-side

## Phase 3: Dashboard sales trend chart
- [ ] Backend: add endpoint `GET /api/reports/sales-trend?days=7` with DTO `DailySalesTrendDto`
- [ ] Frontend: add `reportService.getSalesTrend(days)`
- [ ] Frontend: update `Dashboard.jsx` to render recharts chart for 7-day trend

## Testing / verification
- [ ] `bms-backend` builds successfully (mvn test)
- [ ] `frontend` builds successfully (npm run build)
- [ ] Manual QA checklist for acceptance criteria in each phase

