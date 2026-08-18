import { ToggleButtonGroup, ToggleButton } from '@mui/material';
import { useTranslation } from 'react-i18next';

// Emits { startDate, endDate } as ISO strings for whichever period is selected.
const PeriodToggle = ({ period, onChange }) => {
  const { t } = useTranslation('dashboard');
  const handleChange = (e, newPeriod) => {
    if (!newPeriod) return; // ignore deselect-to-nothing
    const today = new Date();
    let startDate, endDate;

    if (newPeriod === 'today') {
      startDate = endDate = today.toISOString().split('T')[0];
    } else if (newPeriod === 'week') {
      const weekAgo = new Date(today);
      weekAgo.setDate(today.getDate() - 6);
      startDate = weekAgo.toISOString().split('T')[0];
      endDate = today.toISOString().split('T')[0];
    } else if (newPeriod === 'month') {
      startDate = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
      endDate = today.toISOString().split('T')[0];
    }

    onChange(newPeriod, startDate, endDate);
  };

  return (
    <ToggleButtonGroup value={period} exclusive onChange={handleChange} size="small">
      <ToggleButton value="today">{t('period_today')}</ToggleButton>
      <ToggleButton value="week">{t('period_week')}</ToggleButton>
      <ToggleButton value="month">{t('period_month')}</ToggleButton>
    </ToggleButtonGroup>
  );
};

export default PeriodToggle;