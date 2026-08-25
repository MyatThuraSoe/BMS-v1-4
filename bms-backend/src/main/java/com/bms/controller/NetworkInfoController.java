package com.bms.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells devices (and the desktop's own About page) which LAN address this
 * LumiPOS server can be reached on, so staff never have to guess an IP.
 *
 * Public on purpose: it reveals nothing secret, and it must be callable by
 * the desktop UI before login.
 */
@RestController
@RequestMapping("/api/public")
public class NetworkInfoController {

    @Value("${server.port:8080}")
    private int serverPort;

    @GetMapping("/network-info")
    public ResponseEntity<Map<String, Object>> networkInfo() {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Site-local = private LAN ranges (192.168/10./172.16-31)
                    if (addr.isSiteLocalAddress()) {
                        urls.add("http://" + addr.getHostAddress() + ":" + serverPort);
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to whatever the client used to reach us
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("urls", urls);
        return ResponseEntity.ok(result);
    }
}
