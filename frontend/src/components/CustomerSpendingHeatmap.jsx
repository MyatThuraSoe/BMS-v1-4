import { useState, useMemo } from 'react';
import { Box, Typography, Tabs, Tab, Tooltip, Paper } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { saleService } from '../api/services';
import { formatCurrency } from '../utils/helpers';

const CustomerSpendingHeatmap = ({ customerId }) => {
  const currentYear = new Date().getFullYear();
  const [selectedYear, setSelectedYear] = useState(currentYear);

  // Generate available years (from 2024 to current year)
  const availableYears = useMemo(() => {
    const years = [];
    for (let y = 2026; y <= currentYear; y++) {
      years.push(y);
    }
    return years.reverse();
  }, [currentYear]);

  const { data: spendingData, isLoading } = useQuery({
    queryKey: ['customerDailySpending', customerId, selectedYear],
    queryFn: () => saleService.getCustomerDailySpending(customerId, selectedYear),
  });

  const dailySpending = spendingData?.data || [];

  // Create a map for quick lookup: date string -> amount
  const spendingMap = useMemo(() => {
    const map = {};
    dailySpending.forEach(item => {
      map[item.date] = item.totalAmount;
    });
    return map;
  }, [dailySpending]);

  // Calculate color intensity based on spending amount (Blue Theme)
  const getColor = (amount) => {
    if (!amount || amount === 0) return '#f0f4f8'; // Empty / Light blue-gray
    
    // Find max amount for scaling
    const maxAmount = Math.max(...dailySpending.map(d => d.totalAmount || 0), 1);
    const intensity = amount / maxAmount;
    
    if (intensity < 0.25) return '#bbdefb'; // Blue 100
    if (intensity < 0.50) return '#64b5f6'; // Blue 300
    if (intensity < 0.75) return '#1e88e5'; // Blue 600
    return '#0d47a1';                       // Blue 900
  };

  // Generate the grid for the year (guarantees exactly 7 days per column)
  const generateYearGrid = () => {
    const startDate = new Date(selectedYear, 0, 1); // Jan 1
    const endDate = new Date(selectedYear, 11, 31); // Dec 31
    
    // Adjust to start on Sunday (Day 0)
    const startDay = startDate.getDay();
    const adjustedStart = new Date(startDate);
    adjustedStart.setDate(startDate.getDate() - startDay);
    
    const weeks = [];
    let currentWeek = [];
    let currentDate = new Date(adjustedStart);
    let isYearEnded = false;
    
    while (true) {
      const isCurrentYear = currentDate.getFullYear() === selectedYear;
      
      if (isCurrentYear) {
        const dateStr = currentDate.toISOString().split('T')[0];
        const amount = spendingMap[dateStr] || 0;
        currentWeek.push({
          date: dateStr,
          amount,
          color: getColor(amount),
          isCurrentYear: true,
          month: currentDate.getMonth(),
        });
      } else {
        // Days before Jan 1 or after Dec 31
        if (currentDate > endDate) {
          isYearEnded = true;
        }
        currentWeek.push({
          date: null,
          amount: 0,
          color: 'transparent',
          isCurrentYear: false,
          month: currentDate.getMonth(),
        });
      }
      
      // When a week is full (7 days), push it and reset
      if (currentWeek.length === 7) {
        weeks.push(currentWeek);
        currentWeek = [];
        // Break only after the week containing Dec 31 is fully rendered
        if (isYearEnded) break;
      }
      
      currentDate.setDate(currentDate.getDate() + 1);
    }
    
    return weeks;
  };

  const yearGrid = useMemo(() => generateYearGrid(), [selectedYear, spendingMap]);

  // Calculate month label positions based on the first week they appear in
  const monthLabels = useMemo(() => {
    const labels = [];
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const firstWeekOfMonth = {};
    
    yearGrid.forEach((week, wIdx) => {
      week.forEach(day => {
        if (day.isCurrentYear && firstWeekOfMonth[day.month] === undefined) {
          firstWeekOfMonth[day.month] = wIdx;
        }
      });
    });

    for (let m = 0; m < 12; m++) {
      if (firstWeekOfMonth[m] !== undefined) {
        labels.push({
          name: monthNames[m],
          weekIndex: firstWeekOfMonth[m]
        });
      }
    }
    return labels;
  }, [yearGrid]);

  return (
    <Paper sx={{ p: 3, overflow: 'hidden' }}>
      <Typography variant="h6" gutterBottom>Spending Activity</Typography>
      
      <Tabs
        value={selectedYear}
        onChange={(e, newValue) => setSelectedYear(newValue)}
        sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}
      >
        {availableYears.map(year => (
          <Tab key={year} label={year} value={year} sx={{ minWidth: 60 }} />
        ))}
      </Tabs>

      {isLoading ? (
        <Typography color="text.secondary">Loading activity data...</Typography>
      ) : (
        <Box sx={{ overflowX: 'auto', pb: 1 }}>
          {/* Month labels - absolutely positioned based on week index */}
          <Box sx={{ display: 'flex', ml: '30px', mb: 1, position: 'relative', height: '20px' }}>
            {monthLabels.map((label, idx) => (
              <Typography 
                key={idx} 
                variant="caption" 
                sx={{ 
                  position: 'absolute', 
                  left: `${label.weekIndex * 14}px`, // 12px width + 2px gap = 14px per week column
                  fontSize: '11px',
                  fontWeight: 500,
                  color: 'text.secondary'
                }}
              >
                {label.name}
              </Typography>
            ))}
          </Box>

          <Box sx={{ display: 'flex' }}>
            {/* Day labels */}
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: '2px', mr: 1, mt: 0 }}>
              {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((day, idx) => (
                <Typography 
                  key={idx} 
                  variant="caption" 
                  sx={{ 
                    height: '12px', 
                    lineHeight: '12px', 
                    fontSize: '10px',
                    color: 'text.secondary',
                    textAlign: 'right',
                    width: '24px' // Fixed width prevents layout shifting
                  }}
                >
                  {/* Show only Mon, Wed, Fri like GitHub */}
                  {idx === 1 ? 'Mon' : idx === 3 ? 'Wed' : idx === 5 ? 'Fri' : ''}
                </Typography>
              ))}
            </Box>

            {/* Heatmap grid */}
            <Box sx={{ display: 'flex', gap: '2px' }}>
              {yearGrid.map((week, weekIdx) => (
                <Box key={weekIdx} sx={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
                  {week.map((day, dayIdx) => (
                    <Tooltip
                      key={dayIdx}
                      title={
                        day.isCurrentYear && day.date
                          ? `${day.date}: ${day.amount > 0 ? formatCurrency(day.amount) : 'No activity'}`
                          : ''
                      }
                      arrow
                      placement="top"
                    >
                      <Box
                        sx={{
                          width: '12px',
                          height: '12px',
                          backgroundColor: day.color,
                          borderRadius: '2px',
                          cursor: day.isCurrentYear && day.amount > 0 ? 'pointer' : 'default',
                          transition: 'all 0.1s ease',
                          '&:hover': {
                            outline: day.amount > 0 ? '2px solid #1976d2' : 'none',
                            zIndex: 1,
                          },
                        }}
                      />
                    </Tooltip>
                  ))}
                </Box>
              ))}
            </Box>
          </Box>

          {/* Legend */}
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', mt: 2, gap: 1 }}>
            <Typography variant="caption" sx={{ fontSize: '11px', color: 'text.secondary' }}>Less</Typography>
            <Box sx={{ width: '12px', height: '12px', backgroundColor: '#f0f4f8', borderRadius: '2px', border: '1px solid rgba(0,0,0,0.06)' }} />
            <Box sx={{ width: '12px', height: '12px', backgroundColor: '#bbdefb', borderRadius: '2px' }} />
            <Box sx={{ width: '12px', height: '12px', backgroundColor: '#64b5f6', borderRadius: '2px' }} />
            <Box sx={{ width: '12px', height: '12px', backgroundColor: '#1e88e5', borderRadius: '2px' }} />
            <Box sx={{ width: '12px', height: '12px', backgroundColor: '#0d47a1', borderRadius: '2px' }} />
            <Typography variant="caption" sx={{ fontSize: '11px', color: 'text.secondary' }}>More</Typography>
          </Box>
        </Box>
      )}
    </Paper>
  );
};

export default CustomerSpendingHeatmap;