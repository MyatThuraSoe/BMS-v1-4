// src/components/SetupChecklist.jsx
import { useState, useEffect, useMemo } from 'react';
import { 
  Card, 
  CardContent, 
  Typography, 
  List, 
  ListItem, 
  ListItemIcon, 
  ListItemText, 
  LinearProgress, 
  IconButton, 
  Box, 
  Button,
  Skeleton,
  Alert
} from '@mui/material';
import { 
  CheckCircle, 
  RadioButtonUnchecked, 
  Close, 
  ArrowForward,
  Store,
  Category,
  Inventory
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
// FIXED: Import from your existing services file instead of a non-existent axios file
import { shopInfoService, categoryService, productService } from '../api/services';

const DISMISSAL_KEY = 'bms_setup_checklist_dismissed';

export default function SetupChecklist() {
  const navigate = useNavigate();
  const [dismissed, setDismissed] = useState(
    () => localStorage.getItem(DISMISSAL_KEY) === 'true'
  );
  
  // Fetch data using React Query and your existing services
  const { data: shopInfoResponse, isLoading: shopLoading } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: shopInfoService.get,
    staleTime: 5 * 60 * 1000,
  });
  // Extract the actual data payload from the service response wrapper
  const shopInfo = shopInfoResponse?.data;

  const { data: categoriesResponse, isLoading: categoriesLoading } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoryService.getAll(0, 1),
    staleTime: 5 * 60 * 1000,
  });
  const categoriesData = categoriesResponse?.data;

  const { data: productsResponse, isLoading: productsLoading } = useQuery({
    queryKey: ['products', { page: 0, size: 1 }],
    queryFn: () => productService.getAll(0, 1),
    staleTime: 5 * 60 * 1000,
  });
  const productsData = productsResponse?.data;

  const isLoading = shopLoading || categoriesLoading || productsLoading;

  const steps = useMemo(() => [
    {
      id: 'shop-info',
      label: 'Set up your shop information',
      description: 'Add your shop name, address, and logo for receipts',
      icon: <Store />,
      completed: Boolean(shopInfo?.shopName && shopInfo.shopName.trim() !== ''),
      action: () => navigate('/settings/shop-info'),
      buttonText: 'Set Up Shop'
    },
    {
      id: 'category',
      label: 'Create your first category',
      description: 'Organize products into categories like "Beverages" or "Snacks"',
      icon: <Category />,
      completed: (categoriesData?.page?.totalElements ?? 0) > 0,
      action: () => navigate('/categories'),
      buttonText: 'Add Category'
    },
    {
      id: 'product',
      label: 'Add your first product',
      description: 'Start building your inventory',
      icon: <Inventory />,
      completed: (productsData?.page?.totalElements ?? 0) > 0,
      action: () => navigate('/products/new'),
      buttonText: 'Add Product'
    }
  ], [shopInfo, categoriesData, productsData, navigate]);

  const completedCount = steps.filter(s => s.completed).length;
  const progress = (completedCount / steps.length) * 100;
  const allComplete = completedCount === steps.length;

  // Auto-hide when all complete (after a brief celebration moment)
  useEffect(() => {
    if (allComplete && !dismissed) {
      const timer = setTimeout(() => {
        localStorage.setItem(DISMISSAL_KEY, 'true');
        setDismissed(true);
      }, 3000);
      return () => clearTimeout(timer);
    }
  }, [allComplete, dismissed]);

  const handleDismiss = () => {
    localStorage.setItem(DISMISSAL_KEY, 'true');
    setDismissed(true);
  };

  if (dismissed) return null;

  if (isLoading) {
    return (
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Skeleton variant="text" width="60%" height={32} />
          <Skeleton variant="rectangular" height={4} sx={{ my: 2 }} />
          <Skeleton variant="rectangular" height={60} sx={{ mb: 1 }} />
          <Skeleton variant="rectangular" height={60} sx={{ mb: 1 }} />
          <Skeleton variant="rectangular" height={60} />
        </CardContent>
      </Card>
    );
  }

  return (
    <Card 
      sx={{ 
        mb: 3, 
        bgcolor: allComplete ? 'success.light' : 'primary.light',
        transition: 'background-color 0.3s ease'
      }}
    >
      <CardContent>
        <Box display="flex" justifyContent="space-between" alignItems="center" mb={2} sx={{ flexWrap: 'wrap', gap: 1 }}>
          <Typography variant="h6" fontWeight="bold">
            {allComplete ? '🎉 Setup Complete!' : '👋 Welcome! Let\'s get you set up'}
          </Typography>
          <IconButton 
            onClick={handleDismiss} 
            size="small"
            aria-label="Dismiss setup checklist"
          >
            <Close />
          </IconButton>
        </Box>

        {allComplete && (
          <Alert severity="success" sx={{ mb: 2 }}>
            You're all set! You can now start using the system. This card will disappear automatically.
          </Alert>
        )}

        {!allComplete && (
          <>
            <LinearProgress 
              variant="determinate" 
              value={progress} 
              sx={{ 
                mb: 2, 
                height: 8, 
                borderRadius: 4,
                '& .MuiLinearProgress-bar': {
                  borderRadius: 4
                }
              }} 
            />
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {completedCount} of {steps.length} steps completed
            </Typography>
          </>
        )}

        <List dense>
          {steps.map((step, index) => (
            <ListItem 
              key={step.id}
              sx={{ 
                bgcolor: 'background.paper', 
                mb: 1, 
                borderRadius: 2,
                opacity: step.completed ? 0.7 : 1,
                transition: 'opacity 0.3s ease',
                '&:hover': {
                  opacity: 1
                }
              }}
              secondaryAction={
                !step.completed && (
                  <Button 
                    endIcon={<ArrowForward />} 
                    onClick={step.action}
                    size="small"
                    variant="contained"
                    color="primary"
                  >
                    {step.buttonText}
                  </Button>
                )
              }
            >
              <ListItemIcon sx={{ minWidth: 40 }}>
                {step.completed ? (
                  <CheckCircle color="success" fontSize="large" />
                ) : (
                  <Box sx={{ color: 'primary.main' }}>
                    {step.icon}
                  </Box>
                )}
              </ListItemIcon>
              <ListItemText
                primary={
                  <Typography 
                    variant="body1" 
                    fontWeight={step.completed ? 'normal' : 'bold'}
                    sx={{ 
                      textDecoration: step.completed ? 'line-through' : 'none',
                      color: step.completed ? 'text.secondary' : 'text.primary'
                    }}
                  >
                    {`${index + 1}. ${step.label}`}
                  </Typography>
                }
                secondary={step.description}
              />
            </ListItem>
          ))}
        </List>
      </CardContent>
    </Card>
  );
}