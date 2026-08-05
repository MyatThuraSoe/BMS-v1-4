import { Grid, Paper, Typography, Box } from '@mui/material';
import { formatCurrency } from '../utils/helpers';

// One consistent row, everywhere this data appears: Revenue -> Gross Profit -> Expenses -> Net Profit.
// This is the only place that formats/labels these four numbers — Dashboard and Accounting both
// render this same component so they can't drift into different wording or ordering over time.
const FinancialSummaryCards = ({ summary, onCardClick }) => {
  const cards = [
    { key: 'revenue', label: 'Revenue', value: summary?.totalIncome, color: 'text.primary', changePercent: summary?.incomeChangePercent },
    { key: 'grossProfit', label: 'Gross Profit', value: summary?.grossProfit, color: 'info.main', changePercent: null },
    { key: 'expenses', label: 'Expenses', value: summary?.totalExpenses, color: 'error.main', changePercent: null },
    { key: 'netProfit', label: 'Net Profit', value: summary?.netProfit, color: 'success.main', highlight: true, changePercent: summary?.profitChangePercent },
  ];

  return (
    <Grid container spacing={2}>
      {cards.map((card) => (
        <Grid item xs={6} md={3} key={card.key}>
          <Paper
            onClick={onCardClick ? () => onCardClick(card.key) : undefined}
            sx={{
              p: 2,
              cursor: onCardClick ? 'pointer' : 'default',
              '&:hover': onCardClick ? { boxShadow: 4 } : {},
              ...(card.highlight && { border: '2px solid', borderColor: 'success.main' }),
            }}
          >
            <Typography variant="body2" color="text.secondary">{card.label}</Typography>
            <Typography variant={card.highlight ? 'h4' : 'h5'} fontWeight="bold" color={card.color}>
              {formatCurrency(card.value)}
            </Typography>
            {card.changePercent != null && (
              <Typography
                variant="caption"
                color={card.changePercent >= 0 ? 'success.main' : 'error.main'}
                sx={{ display: 'block', mt: 0.5 }}
              >
                {card.changePercent >= 0 ? '▲' : '▼'} {Math.abs(Number(card.changePercent)).toFixed(1)}% vs previous period
              </Typography>
            )}
            {card.changePercent == null && summary != null && card.key === 'revenue' && summary.incomeChangePercent == null && (
              <Typography variant="caption" color="text.disabled" sx={{ display: 'block', mt: 0.5 }}>
                N/A — no prior period data
              </Typography>
            )}
          </Paper>
        </Grid>
      ))}
    </Grid>
  );
};

export default FinancialSummaryCards;