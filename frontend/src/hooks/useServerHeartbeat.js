import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';

const HEARTBEAT_INTERVAL_MS = 5000;
const HEALTH_TIMEOUT_MS = 4000;

// A laptop or tablet on the shop Wi-Fi can lose the LumiPOS server (sleep,
// Wi-Fi drop, cable) while the browser tab is still open. The cashier would
// only discover it when a sale fails, by which time the cart is on screen and
// the customer is waiting. Poll the cheap /api/health endpoint so the UI can
// warn the moment the link dies, then quietly resume when it returns.
async function probeHealth() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);
  try {
    const res = await fetch('/api/health', { signal: controller.signal, cache: 'no-store' });
    // Any answer at all (even 401/403/500) proves the server is reachable and
    // answering. Only a thrown error means the connection itself is gone.
    return res.status < 500;
  } catch (err) {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

export default function useServerHeartbeat() {
  const [isOnline, setIsOnline] = useState(true);
  const [downSince, setDownSince] = useState(null);
  const [checking, setChecking] = useState(false);
  const queryClient = useQueryClient();
  const wasOfflineRef = useRef(false);

  useEffect(() => {
    let active = true;

    const check = async () => {
      const online = await probeHealth();
      if (!active) return;

      setChecking(false);

      if (online) {
        setIsOnline(true);
        setDownSince(null);
        if (wasOfflineRef.current) {
          wasOfflineRef.current = false;
          // The server came back. Refetch whatever the cashier is looking at so
          // stock levels and prices are current. The cart in local component
          // state is deliberately untouched: a blip must not wipe a sale.
          queryClient.invalidateQueries();
        }
      } else {
        if (!wasOfflineRef.current) {
          wasOfflineRef.current = true;
          setDownSince(new Date());
        }
        setIsOnline(false);
      }
    };

    const ping = () => {
      setChecking(true);
      check();
    };

    ping();
    const intervalId = setInterval(check, HEARTBEAT_INTERVAL_MS);

    // A tab restored from the background was probably asleep: probe at once
    // instead of waiting out the interval.
    const onVisible = () => {
        if (document.visibilityState === 'visible') check();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', check);
    window.addEventListener('offline', check);

    return () => {
      active = false;
      clearInterval(intervalId);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', check);
      window.removeEventListener('offline', check);
    };
  }, [queryClient]);

  return { isOnline, checking, downSince };
}
