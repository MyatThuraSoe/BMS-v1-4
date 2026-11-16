package com.bms.service;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * N7 removed the inline {@code new ShopTimeService(null, "UTC")} fallbacks from
 * the services, so a unit test that builds one with {@code new} has to wire the
 * clock itself. The real component with no settings row behaves exactly like
 * the fallback it replaced.
 */
final class ShopTimeTestSupport {

    private ShopTimeTestSupport() {
    }

    static void withClock(Object service) {
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "UTC"));
    }
}
