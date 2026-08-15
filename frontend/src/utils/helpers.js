let cachedCurrencyCode = 'USD'; // sensible default before the real setting loads

export const setCurrencyCode = (code) => { cachedCurrencyCode = code || 'USD'; };

export const formatCurrency = (amount) => {
  try {
    // Myanmar Kyat — display as "Ks" instead of the "MMK" code
    if (cachedCurrencyCode === 'MMK') {
      const formatted = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(amount || 0);
      return `${formatted} Ks`;
    }
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: cachedCurrencyCode,
    }).format(amount || 0);
  } catch {
    // Invalid/unsupported currency code — fail safe rather than crash the page
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount || 0);
  }
};

export const formatDate = (dateString) => {
  if (!dateString) return '';
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
};

export const formatDateTime = (dateString) => {
  if (!dateString) return '';
  return new Date(dateString).toLocaleString('en-US');
};

export const validateEmail = (email) => {
  const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return re.test(email);
};

export const validatePhone = (phone) => {
  const re = /^[\d\s\-\+\(\)]+$/;
  return re.test(phone);
};
