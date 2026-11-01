// Expiry is optional per product. Products that do not track it (hasExpiry
// false) or have no date yet simply have no status. "Expiring soon" means the
// date is within EXPIRING_SOON_DAYS from today (or today itself); anything in
// the past is expired.
export const EXPIRING_SOON_DAYS = 30;

export const getExpiryStatus = (product) => {
  if (!product?.hasExpiry || !product?.expiryDate) {
    return null;
  }

  const today = new Date();
  today.setHours(0, 0, 0, 0);

  const expiry = new Date(`${product.expiryDate}T00:00:00`);
  if (Number.isNaN(expiry.getTime())) {
    return null;
  }

  const daysLeft = Math.round((expiry.getTime() - today.getTime()) / (24 * 60 * 60 * 1000));

  if (daysLeft < 0) {
    return { status: 'expired', daysLeft, date: product.expiryDate };
  }
  if (daysLeft <= EXPIRING_SOON_DAYS) {
    return { status: 'expiring', daysLeft, date: product.expiryDate };
  }
  return { status: 'ok', daysLeft, date: product.expiryDate };
};

export const formatExpiryDate = (dateStr) => {
  if (!dateStr) return '-';
  const date = new Date(`${dateStr}T00:00:00`);
  return Number.isNaN(date.getTime()) ? dateStr : date.toLocaleDateString();
};
