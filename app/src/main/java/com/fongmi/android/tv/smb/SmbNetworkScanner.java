package com.fongmi.android.tv.smb;

/**
 * 局域网 SMB 主机扫描：只扫描物理网卡上的私有 IPv4 网段（最多按 /24 收窄），
 * 对 445 / 4450 端口做带超时的 TCP 探测。扫描结果是主机 IP 列表，不在这里做登录或共享枚举；
 * 登录和共享选择由 {@link com.fongmi.android.tv.ui.activity.SmbBrowserActivity} 处理。
 */

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SmbNetworkScanner {

    private SmbNetworkScanner() {
    }

    public static List<String> scan() throws Exception {
        Set<String> targets = new LinkedHashSet<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            try {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                String interfaceName = network.getName().toLowerCase(java.util.Locale.ROOT);
                // VPN/蜂窝隧道不是 SMB 所在的物理局域网，跳过以免重复扫描无关网段。
                if (interfaceName.startsWith("tun") || interfaceName.startsWith("ppp")
                        || interfaceName.startsWith("rmnet") || interfaceName.startsWith("dummy")) continue;
            } catch (Exception ignored) {
                continue;
            }
            for (java.net.InterfaceAddress item : network.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                byte[] bytes = address.getAddress();
                int ip = ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16)
                        | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
                if (!isPrivate(ip)) continue;
                int prefix = item.getNetworkPrefixLength();
                if (prefix < 1 || prefix > 30) continue;
                // Avoid sweeping huge routed networks; SMB discovery is scoped to the local /24.
                int mask = prefix < 24 ? 0xffffff00 : (int) (0xffffffffL << (32 - prefix));
                int networkAddress = ip & mask;
                int broadcast = networkAddress | ~mask;
                for (int candidate = networkAddress + 1; candidate < broadcast; candidate++) {
                    if (candidate == ip) continue;
                    targets.add(toIp(candidate));
                }
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(96);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (final String host : targets) {
                tasks.add(new Callable<String>() {
                    @Override
                    public String call() {
                        // 445 是标准 SMB 端口；4450 是本项目手机端分享用的高位端口
                        // （非 root 绑不了 445）。扫到 4450 时主机名带上端口，
                        // 浏览页据此直连共享、跳过 srvsvc 枚举。
                        if (probe(host, 445)) return host;
                        if (probe(host, 4450)) return host + ":4450";
                        return null;
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 3, TimeUnit.SECONDS);
            List<String> found = new ArrayList<>();
            for (Future<String> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    String host = future.get();
                    if (host != null) found.add(host);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(found, (a, b) -> Long.compare(ipValue(a), ipValue(b)));
            return found;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 180);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long ipValue(String value) {
        int cut = value.indexOf(':');
        String[] parts = (cut > 0 ? value.substring(0, cut) : value).split("\\.");
        long result = 0;
        for (String part : parts) result = (result << 8) | Integer.parseInt(part);
        return result;
    }

    private static boolean isPrivate(int ip) {
        int a = (ip >>> 24) & 255;
        int b = (ip >>> 16) & 255;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }


    private static String toIp(int value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }
}
