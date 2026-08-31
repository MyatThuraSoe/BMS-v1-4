import React from 'react';
import { Box, Paper, Typography, Button } from '@mui/material';
import { ErrorOutline as ErrorIcon } from '@mui/icons-material';

/**
 * Top-level safety net: if any render-time error escapes, show a friendly
 * recovery screen instead of a white page.
 */
class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error, info) {
    // eslint-disable-next-line no-console
    console.error('Unhandled UI error:', error, info?.componentStack);
  }

  handleReload = () => {
    window.location.reload();
  };

  handleGoBack = () => {
    window.history.back();
  };

  render() {
    if (this.state.hasError) {
      return (
        <Box
          sx={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: 'background.default',
            p: 2,
          }}
        >
          <Paper sx={{ p: 5, maxWidth: 420, textAlign: 'center' }}>
            <ErrorIcon color="error" sx={{ fontSize: 56, mb: 1 }} />
            <Typography variant="h6" gutterBottom>Something went wrong</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              An unexpected error occurred. Your data is safe.
              Reload the app to continue.
            </Typography>
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', justifyContent: 'center' }}>
              <Button variant="contained" onClick={this.handleReload}>
                Reload LumiPOS
              </Button>
              <Button variant="outlined" onClick={this.handleGoBack}>
                Go Back
              </Button>
            </Box>
          </Paper>
        </Box>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
