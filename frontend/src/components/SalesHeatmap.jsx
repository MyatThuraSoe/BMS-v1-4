import { useMemo } from 'react';
import { Box, Typography, Tooltip as MuiTooltip } from '@mui/material';
import { useTranslation } from 'react-i18next';

const SalesHeatmap = ({ data }) => {
  const { t } = useTranslation('reports');
  const dayLabels = [t('day_mon'), t('day_tue'), t('day_wed'), t('day_thu'), t('day_fri'), t('day_sat'), t('day_sun')];
  const grid = useMemo(() => {
    const g = {};
    for (let d = 1; d <= 7; d++) {
      for (let h = 0; h <= 23; h++) {
        g[`${d}_${h}`] = 0;
      }
    }
    for (const row of data) {
      g[`${row.dayOfWeek}_${row.hourOfDay}`] = row.transactionCount;
    }
    return g;
  }, [data]);

  const maxCount = useMemo(() => Math.max(1, ...data.map((r) => r.transactionCount)), [data]);

  const activeHours = useMemo(() => {
    const hours = new Set(data.map((r) => r.hourOfDay));
    if (hours.size === 0) return Array.from({ length: 24 }, (_, i) => i);
    const min = Math.max(0, Math.min(...hours) - 1);
    const max = Math.min(23, Math.max(...hours) + 1);
    return Array.from({ length: max - min + 1 }, (_, i) => min + i);
  }, [data]);

  const getColor = (count) => {
    if (count === 0) return '#f5f5f5';
    const intensity = count / maxCount;
    const r = Math.round(25 + (1 - intensity) * 180);
    const g = Math.round(118 + (1 - intensity) * 100);
    const b = Math.round(210 * (1 - intensity * 0.7));
    return `rgb(${r},${g},${b})`;
  };

  return (
    <Box sx={{ overflowX: 'auto' }}>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr>
            <th style={{ width: 40, fontSize: 11, color: '#666', textAlign: 'right', paddingRight: 8 }}>{t('hour')}</th>
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
                const count = grid[`${d}_${h}`];
                return (
                  <MuiTooltip
                    key={d}
                    title={t('heatmap_transactions', { day: dayLabels[d - 1], time: h.toString().padStart(2, '0'), count })}
                  >
                    <td
                      style={{
                        backgroundColor: getColor(count),
                        width: 'calc((100% - 48px) / 7)',
                        height: 20,
                        border: '1px solid #fff',
                        cursor: 'default',
                        textAlign: 'center',
                        fontSize: 9,
                        color: count > maxCount * 0.6 ? '#fff' : 'transparent',
                      }}
                    >
                      {count > 0 ? count : ''}
                    </td>
                  </MuiTooltip>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, justifyContent: 'flex-end' }}>
        <Typography variant="caption" color="text.secondary">{t('low')}</Typography>
        {[0.1, 0.3, 0.5, 0.7, 0.9].map((v) => (
          <Box key={v} sx={{ width: 16, height: 16, backgroundColor: getColor(v * maxCount), border: '1px solid #eee', borderRadius: 0.5 }} />
        ))}
        <Typography variant="caption" color="text.secondary">{t('high')}</Typography>
      </Box>
    </Box>
  );
};

export default SalesHeatmap;