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

export const formatReceiptCurrency = (amount) => {
  try {
    if (cachedCurrencyCode === 'MMK') {
      return `${new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount || 0)} Ks`;
    }
    const parts = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: cachedCurrencyCode,
    }).formatToParts(amount || 0);
    const currencyPart = parts.find((part) => part.type === 'currency')?.value || '$';
    const numberPart = parts
      .filter((part) => part.type !== 'currency')
      .map((part) => part.value)
      .join('')
      .trim();
    return `${numberPart} ${currencyPart}`;
  } catch {
    return `${new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount || 0)} $`;
  }
};

// Numeric amount without the currency unit — used in receipt item columns
export const formatAmountPlain = (amount) => {
  try {
    return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount || 0);
  } catch {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount || 0);
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

export const toLocalDateString = (date) => {
  if (!date) return '';
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
};

export const formatDateTime = (dateString) => {
  if (!dateString) return '';
  return new Date(dateString).toLocaleString('en-US');
};

export const formatReceiptDateTime = (dateString, timeFormat = '12') => {
  if (!dateString) return '';
  const date = new Date(dateString);
  if (Number.isNaN(date.getTime())) return '';
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const datePart = `${year}-${month}-${day}`;
  const hours = date.getHours();
  const minutes = String(date.getMinutes()).padStart(2, '0');
  if (String(timeFormat) === '24') {
    return `${datePart} ${String(hours).padStart(2, '0')}:${minutes}`;
  }
  const hour12 = (hours % 12 === 0) ? 12 : hours % 12;
  const ampm = hours < 12 ? 'am' : 'pm';
  return `${datePart} ${hour12}:${minutes}${ampm}`;
};

export const validateEmail = (email) => {
  const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return re.test(email);
};

export const validatePhone = (phone) => {
  const re = /^[\d\s+()-]+$/;
  return re.test(phone);
};

// --- Receipt paper geometry (mm → px/chars) ---
// Receipt paper width in characters (12 dots/char @ 203dpi ≈ 1.47mm/char).
const CHARS_PER_MM = 1 / 1.47;

function parsePaperWidthMm(paperSize) {
  if (paperSize == null || paperSize === '') return 58;
  const digits = String(paperSize).replace(/\D/g, '');
  const mm = parseInt(digits, 10);
  if (!Number.isFinite(mm) || mm < 20 || mm > 200) return 58;
  return mm;
}

export const getReceiptLineWidth = (paperSize) => {
  const mm = parsePaperWidthMm(paperSize);
  return Math.max(16, Math.round(mm * CHARS_PER_MM));
};

export const getReceiptPreviewWidth = (paperSize) => {
  const mm = parsePaperWidthMm(paperSize);
  return Math.round(mm * (400 / 58));
};

export const downloadCsv = (filename, rows, headers) => {
  if (!rows || rows.length === 0) return;
  const keys = Object.keys(rows[0]);
  const escapeCell = (v) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [
    (headers || keys).map(escapeCell).join(','),
    ...rows.map((r) => keys.map((h) => escapeCell(r[h])).join(',')),
  ];
  const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filename}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};
