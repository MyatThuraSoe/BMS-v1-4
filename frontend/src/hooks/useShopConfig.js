import { useQuery } from '@tanstack/react-query';
import { shopInfoService } from '../api/services';

/**
 * Single source of truth for shop configuration (name, currency, tax,
 * discount settings). Share ONE react-query cache key so an update in
 * Shop Info instantly refreshes POS, dashboard, receipts, everywhere.
 *
 * Usage:
 *   const { data } = useShopConfig();                 // anywhere
 *   const { data } = useShopConfig({ enabled: isAdmin() });
 */
export const SHOP_CONFIG_QUERY_KEY = 'shopInfo';

export default function useShopConfig(options = {}) {
  return useQuery({
    queryKey: [SHOP_CONFIG_QUERY_KEY],
    queryFn: () => shopInfoService.get(),
    staleTime: 60 * 1000,
    ...options,
  });
}
