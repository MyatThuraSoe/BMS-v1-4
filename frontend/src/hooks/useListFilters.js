import { useMemo, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

/**
 * useListFilters — persists list-page filters in the URL query string.
 *
 * This keeps filter/range/pagination state when navigating to a detail view
 * and back (and on browser back / refresh), instead of dropping it on unmount.
 *
 * schema: { [param]: { init, parse?, serialize? } }
 *   init      default value when the param is absent from the URL
 *   parse     (rawString) => value            (default: identity)
 *   serialize (value) => string|number|null   (default: value)
 *
 * Updates merge with any other query params already present, so multiple
 * independent lists sharing one route (e.g. tabs on /inventory) can each
 * hold their own keys without wiping each other.
 *
 * Returns:
 *   filters    current values as a plain object
 *   update(partial, opts)  merge partial filters into the URL; opts passed to setSearchParams
 *   reset(opts)            drop this list's query params from the URL
 *   query                  URLSearchParams of the current URL
 *   detailUrl(basePath)    basePath carrying the same query string, for "go to detail" nav
 */
export function useListFilters(schema) {
  const [searchParams, setSearchParams] = useSearchParams();

  const filters = useMemo(() => {
    const out = {};
    for (const [key, def] of Object.entries(schema)) {
      const raw = searchParams.get(key);
      out[key] = raw == null ? def.init : (def.parse ? def.parse(raw) : raw);
    }
    return out;
  }, [searchParams, schema]);

  const toParams = useCallback((partial, base, current) => {
    const params = new URLSearchParams(base);
    for (const [key, def] of Object.entries(schema)) {
      const value = key in partial ? partial[key] : current[key];
      const serialized = def.serialize ? def.serialize(value) : value;
      if (serialized == null || serialized === '' || String(serialized) === String(def.init)) {
        params.delete(key);
      } else {
        params.set(key, String(serialized));
      }
    }
    return params;
  }, [schema]);

  const update = useCallback((partial, opts) => {
    setSearchParams(toParams(partial, searchParams, filters), opts);
  }, [toParams, searchParams, filters, setSearchParams]);

  const reset = useCallback((opts) => {
    const params = new URLSearchParams(searchParams);
    for (const key of Object.keys(schema)) params.delete(key);
    setSearchParams(params, opts);
  }, [searchParams, setSearchParams, schema]);

  const query = useMemo(() => new URLSearchParams(searchParams), [searchParams]);

  const detailUrl = useCallback((basePath) => {
    const qs = query.toString();
    return qs ? `${basePath}?${qs}` : basePath;
  }, [query]);

  return { filters, update, reset, query, detailUrl };
}

export default useListFilters;