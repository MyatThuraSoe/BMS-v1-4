import { useMemo, useState } from 'react';
import {
  Box, Typography, Paper, ToggleButtonGroup, ToggleButton, Tooltip as MuiTooltip,
  CircularProgress, Alert, Chip,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { productService } from '../api/services';
import { formatCurrency } from '../utils/helpers';

const PERIODS = ['7d', '1m', '1y', 'all'];

// Weekday x hour sales heat map for a single product. Each period is a
// separate query, so the 1-month / 1-year / all-time windows hit the backend
// only when their tab is selected - a busy shop's database is never asked for
// a long range up front.
const ProductSaleHeatmap = ({ productId }) => {
  const { t } = useTranslation(['inventory', 'reports']);
  const [period, setPeriod] = useState('7d');

  const { data, isLoading, isError } = useQuery({
    queryKey: ['product-sale-heatmap', productId, period],
    queryFn: () => productService.getSaleHeatmap(productId, period),
    enabled: !!productId,
    staleTime: 5 * 60 * 1000,
  });

  const heatmap = data?.data;
  const cells = useMemo(() => heatmap?.cells || [], [heatmap]);

  const dayLabels = [
    t('reports:day_mon'), t('reports:day_tue'), t('reports:day_wed'),
    t('reports:day_thu'), t('reports:day_fri'), t('reports:day_sat'),
    t('reports:day_sun'),
  ];

  const grid = useMemo(() => {
    const g = {};
    for (let d = 1; d <= 7; d++) {
      for (let h = 0; h <= 23; h++) {
        g[`${d}_${h}`] = 0;
      }
    }
    for (const cell of cells) {
      g[`${cell.dayOfWeek}_${cell.hourOfDay}`] = cell.quantity;
    }
    return g;
  }, [cells]);

  const maxQuantity = useMemo(() => Math.max(1, ...cells.map((c) => c.quantity)), [cells]);

  const activeHours = useMemo(() => {
    const hours = new Set(cells.map((c) => c.hourOfDay));
    if (hours.size === 0) return Array.from({ length: 24 }, (_, i) => i);
    const min = Math.max(0, Math.min(...hours) - 1);
    const max = Math.min(23, Math.max(...hours) + 1);
    return Array.from({ length: max - min + 1 }, (_, i) => min + i);
  }, [cells]);

  const cellByKey = useMemo(() => {
    const m = {};
    for (const cell of cells) {
      m[`${cell.dayOfWeek}_${cell.hourOfDay}`] = cell;
    }
    return m;
  }, [cells]);

  const getColor = (quantity) => {
    if (quantity === 0) return '#f5f5f5';
    const intensity = quantity / maxQuantity;
    const r = Math.round(25 + (1 - intensity) * 180);
    const g = Math.round(118 + (1 - intensity) * 100);
    const b = Math.round(210 * (1 - intensity * 0.7));
    return `rgb(${r},${g},${b})`;
  };

  return (
    <Paper sx={{ p: 3, mb: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2, mb: 0.5 }}>
        <Typography variant="h6">{t('sale_heatmap')}</Typography>
        <ToggleButtonGroup
          value={period}
          exclusive
          size="small"
          onChange={(e, value) => value && setPeriod(value)}
        >
          {PERIODS.map((p) => (
            <ToggleButton key={p} value={p}>{t(`heatmap_period_${p}`)}</ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
        {t('sale_heatmap_description')}
      </Typography>

      {isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}>
          <CircularProgress size={28} />
        </Box>
      ) : isError ? (
        <Alert severity="error">{t('heatmap_load_failed')}</Alert>
      ) : (
        <>
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
            <Chip
              size="small"
              color="primary"
              variant="outlined"
              label={`${t('heatmap_total_units')}: ${heatmap?.totalQuantity ?? 0}`}
            />
            <Chip
              size="small"
              color="success"
              variant="outlined"
              label={`${t('heatmap_total_revenue')}: ${formatCurrency(heatmap?.totalRevenue ?? 0)}`}
            />
            <Chip
              size="small"
              variant="outlined"
              label={`${t('heatmap_transactions_count')}: ${heatmap?.transactionCount ?? 0}`}
            />
            <Chip
              size="small"
              variant="outlined"
              label={heatmap?.fromDate
                ? t('heatmap_range', { from: heatmap.fromDate, to: heatmap.toDate })
                : t('heatmap_all_time')}
            />
          </Box>

          {cells.length === 0 ? (
            <Alert severity="info">{t('heatmap_no_sales')}</Alert>
          ) : (
            <Box sx={{ overflowX: 'auto' }}>
              <table style={{ borderCollapse: 'collapse', width: '100%' }}>
                <thead>
                  <tr>
                    <th style={{ width: 40, fontSize: 11, color: '#666', textAlign: 'right', paddingRight: 8 }}>
                      {t('reports:hour')}
                    </th>
                    {dayLabels.map((d) => (
                      <th key={d} style={{ fontSize: 11, color: '#666', textAlign: 'center', padding: '4px 2px' }}>{d}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {activeHours.map((h) => (
                    <tr key={h}>
                      <td style={{ fontSize: 10, color: '#999', textAlign: 'right', paddingRight: 8, paddingBottom: 2 }}>
                        {h.toString().padStart(2, '0')}:00
                      </td>
                      {Array.from({ length: 7 }, (_, di) => di + 1).map((d) => {
                        const quantity = grid[`${d}_${h}`];
                        const cell = cellByKey[`${d}_${h}`];
                        return (
                          <MuiTooltip
                            key={d}
                            title={(
                              <span>
                                {t('reports:heatmap_transactions', {
                                  day: dayLabels[d - 1],
                                  time: h.toString().padStart(2, '0'),
                                  count: cell?.transactionCount ?? 0,
                                })}
                                <br />
                                {t('heatmap_units_revenue', {
                                  quantity,
                                  revenue: formatCurrency(cell?.revenue ?? 0),
                                })}
                              </span>
                            )}
                          >
                            <td
                              style={{
                                backgroundColor: getColor(quantity),
                                width: 'calc((100% - 48px) / 7)',
                                height: 20,
                                border: '1px solid #fff',
                                cursor: 'default',
                                textAlign: 'center',
                                fontSize: 9,
                                color: quantity > maxQuantity * 0.6 ? '#fff' : 'transparent',
                              }}
                            >
                              {quantity > 0 ? quantity : ''}
                            </td>
                          </MuiTooltip>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, justifyContent: 'flex-end' }}>
                <Typography variant="caption" color="text.secondary">{t('reports:low')}</Typography>
                {[0.1, 0.3, 0.5, 0.7, 0.9].map((v) => (
                  <Box
                    key={v}
                    sx={{
                      width: 16, height: 16, backgroundColor: getColor(v * maxQuantity),
                      border: '1px solid #eee', borderRadius: 0.5,
                    }}
                  />
                ))}
                <Typography variant="caption" color="text.secondary">{t('reports:high')}</Typography>
              </Box>
            </Box>
          )}
        </>
      )}
    </Paper>
  );
};

export default ProductSaleHeatmap;
