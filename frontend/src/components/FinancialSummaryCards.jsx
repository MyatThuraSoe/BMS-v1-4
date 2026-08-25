import { Grid, Paper, Typography, Box } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { formatCurrency } from '../utils/helpers';

// One consistent row, everywhere this data appears: Revenue -> Gross Profit -> Expenses -> Net Profit.
// This is the only place that formats/labels these four numbers — Dashboard and Accounting both
// render this same component so they can't drift into different wording or ordering over time.
const FinancialSummaryCards = ({ summary, onCardClick }) => {
  const { t } = useTranslation('dashboard');
  const cards = [
    { key: 'revenue', label: t('revenue'), value: summary?.totalIncome, color: 'text.primary', changePercent: summary?.incomeChangePercent },
    { key: 'grossProfit', label: t('gross_profit'), value: summary?.grossProfit, color: 'info.main', changePercent: null },
    { key: 'expenses', label: t('expenses'), value: summary?.totalExpenses, color: 'error.main', changePercent: null },
    { key: 'netProfit', label: t('net_profit'), value: summary?.netProfit, color: 'success.main', highlight: true, changePercent: summary?.profitChangePercent },
  ];

  return (
    <Grid container spacing={2}>
      {cards.map((card) => (
        <Grid item xs={6} md={3} key={card.key} sx={{ minWidth: 0 }}>
          <Paper
            onClick={onCardClick ? () => onCardClick(card.key) : undefined}
            sx={{
              p: 2,
              minWidth: 0,
              overflow: 'hidden',
              cursor: onCardClick ? 'pointer' : 'default',
              '&:hover': onCardClick ? { boxShadow: 4 } : {},
              ...(card.highlight && { border: '2px solid', borderColor: 'success.main' }),
            }}
          >
            <Typography variant="body2" color="text.secondary" noWrap>
              {card.label}
            </Typography>
            {/* Fluid size: big amounts shrink to fit their box instead of
                spilling over the card edges (MMK values get very long). */}
            <Typography
              component="div"
              noWrap
              fontWeight={card.highlight ? 800 : 700}
              color={card.color}
              sx={{
                fontSize: {
                  xs: 'clamp(0.85rem, 4.2vw, 1.25rem)',
                  sm: 'clamp(1rem, 3vw, 1.45rem)',
                  md: 'clamp(1rem, 1.6vw, 1.4rem)',
                },
                lineHeight: 1.25,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {formatCurrency(card.value)}
            </Typography>
          </Paper>
        </Grid>
      ))}
    </Grid>
  );
};

export default FinancialSummaryCards;