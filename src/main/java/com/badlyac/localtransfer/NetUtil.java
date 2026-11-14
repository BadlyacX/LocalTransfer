package com.badlyac.localtransfer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NetUtil {
    private NetUtil() {
    }

    public static List<String> localIPv4() {
        List<String> ips = new ArrayList<>();
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return ips;
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        ips.sort(Comparator.comparingInt(NetUtil::addressRank));
        return ips;
    }

    public static int addressRank(String ip) {
        if (ip.startsWith("192.168.")) return 0;
        if (ip.startsWith("10.")) return 1;
        if (ip.startsWith("172.")) return 2;
        return 3;
    }

    public static List<InetAddress> broadcastAddresses() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) set.add(ia.getBroadcast());
                }
            }
            set.add(InetAddress.getByName("255.255.255.255"));
            set.add(InetAddress.getByName("127.0.0.1"));
        } catch (Exception ignored) {
        }
        return new ArrayList<>(set);
    }
}
