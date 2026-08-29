const DRAFTS_STORAGE_KEY = 'lumipos_drafts';
const DRAFT_TTL_MS = 12 * 60 * 60 * 1000;

const getStorage = () => {
  if (typeof window === 'undefined') return null;

  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
};

const sanitizeItem = (item = {}) => ({
  productId: item.productId ?? item.id ?? null,
  quantity: Number(item.quantity) > 0 ? Number(item.quantity) : 1,
  productName: item.productName ?? item.name ?? '',
  unitPrice: Number(item.unitPrice ?? item.price ?? 0),
});

const pruneExpiredDrafts = (drafts = []) => {
  const now = Date.now();

  return drafts.filter((draft) => {
    const expiresAt = Number(draft?.expiresAt ?? 0);
    return Number.isFinite(expiresAt) ? expiresAt > now : true;
  });
};

export const readDrafts = () => {
  const storage = getStorage();
  if (!storage) return [];

  try {
    const raw = storage.getItem(DRAFTS_STORAGE_KEY);
    if (!raw) return [];

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];

    const cleaned = pruneExpiredDrafts(parsed);
    if (cleaned.length !== parsed.length) {
      storage.setItem(DRAFTS_STORAGE_KEY, JSON.stringify(cleaned));
    }

    return cleaned;
  } catch {
    storage.removeItem(DRAFTS_STORAGE_KEY);
    return [];
  }
};

export const writeDrafts = (drafts = []) => {
  const storage = getStorage();
  if (!storage) return;

  const cleaned = pruneExpiredDrafts(Array.isArray(drafts) ? drafts : []).map((draft) => ({
    ...draft,
    items: Array.isArray(draft.items) ? draft.items.map(sanitizeItem) : [],
    expiresAt: Number(draft.expiresAt) || Date.now() + DRAFT_TTL_MS,
  }));

  storage.setItem(DRAFTS_STORAGE_KEY, JSON.stringify(cleaned));
};

export const createDraftRecord = ({
  id,
  items = [],
  customerId = null,
  createdAt = new Date().toISOString(),
}) => ({
  id,
  createdAt,
  customerId,
  expiresAt: Date.now() + DRAFT_TTL_MS,
  items: items.map(sanitizeItem),
});

export { DRAFTS_STORAGE_KEY, DRAFT_TTL_MS };
