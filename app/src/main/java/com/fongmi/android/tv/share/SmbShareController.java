package com.fongmi.android.tv.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机 SMB 分享的开关与状态：谁在分享、分享哪个目录、端口多少、局域网地址是什么。
 *
 * <p>放在 main 源集，电视端和手机端共用：电视把 U 盘/下载目录分享出去，手机能像访问网上邻居
 * 一样浏览播放；反过来手机分享、电视访问也是同一套代码。</p>
 */
public final class SmbShareController {

    public static final String SHARE = SmbShareServer.SHARE_NAME;

    private static final String PREF = "smb_share";
    private static final String KEY_ROOT = "root";
    private static final String KEY_PORT = "port";
    private static final String KEY_USER = "user";
    private static final String KEY_PASSWORD = "password";

    private static final Object LOCK = new Object();
    private static SmbShareServer server;

    private SmbShareController() {
    }

    private static SmbShareServer server(Context context) {
        synchronized (LOCK) {
            if (server == null) server = new SmbShareServer();
            return server;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int port(Context context) {
        return prefs(context).getInt(KEY_PORT, SmbShareServer.DEFAULT_PORT);
    }

    /**
     * 共享名固定 webhtv，不再允许修改：改了只会让另一端的连接更难对上，
     * 对“点一下就能连”没有任何收益（用户误以为那是主机名）。
     */
    public static String share(Context context) {
        return SmbShareServer.SHARE_NAME;
    }

    /** 分享账号：与口令一起设置；两者都为空时是匿名开放。 */
    public static String user(Context context) {
        String value = prefs(context).getString(KEY_USER, "");
        return value == null ? "" : value;
    }

    /** 分享口令：空串表示免密。 */
    public static String password(Context context) {
        String value = prefs(context).getString(KEY_PASSWORD, "");
        return value == null ? "" : value;
    }

    /** 默认分享外部存储根目录：没选过就整台设备，选过就用选中的目录。 */
    public static File root(Context context) {
        String path = prefs(context).getString(KEY_ROOT, null);
        if (path != null && !path.isEmpty()) {
            File stored = new File(path);
            if (stored.isDirectory()) return stored;
        }
        File external = Environment.getExternalStorageDirectory();
        return external != null ? external : context.getFilesDir();
    }

    public static boolean isSharing(Context context) {
        return server(context).isRunning();
    }

    /** 启动服务端（不发前台服务通知，给 Service 内部调用）。 */
    public static boolean startServer(Context context, File root, int port) {
        return startServer(context, root, port, user(context), password(context), share(context));
    }

    public static boolean startServer(Context context, File root, int port, String user, String password) {
        return startServer(context, root, port, user, password, share(context));
    }

    public static boolean startServer(Context context, File root, int port, String user, String password, String share) {
        SmbShareServer instance = server(context);
        String account = user == null ? "" : user.trim();
        String secret = password == null ? "" : password;
        String name = SmbShareServer.SHARE_NAME;
        if (instance.isRunning()) {
            if (instance.getPort() == port && root != null && root.equals(instance.getRoot())
                    && account.equals(instance.getUsername()) && secret.equals(instance.getPassword())
                    && name.equalsIgnoreCase(instance.getShareName())) return true;
            instance.stop();
        }
        boolean started = instance.start(root, port, account, secret, name);
        if (started) {
            prefs(context).edit()
                    .putString(KEY_ROOT, root.getAbsolutePath())
                    .putInt(KEY_PORT, port)
                    .putString(KEY_USER, account)
                    .putString(KEY_PASSWORD, secret)
                    .apply();
        }
        return started;
    }

    /** 保存分享设置；已经开着分享就按新设置重启服务端，立刻生效。 */
    public static boolean apply(Context context, File root, String user, String password, int port) {
        File target = root != null && root.isDirectory() ? root : root(context);
        String account = user == null ? "" : user.trim();
        String secret = password == null ? "" : password;
        prefs(context).edit()
                .putString(KEY_ROOT, target.getAbsolutePath())
                .putInt(KEY_PORT, port)
                .putString(KEY_USER, account)
                .putString(KEY_PASSWORD, secret)
                .apply();
        if (!isSharing(context)) return true;
        return startServer(context, target, port, account, secret);
    }

    public static void stopServer(Context context) {
        server(context).stop();
    }

    /** 局域网里可以拿去访问的地址，形如 {@code 192.168.0.5:4450}。 */
    public static List<String> addresses(Context context) {
        int port = server(context).isRunning() ? server(context).getPort() : port(context);
        List<String> result = new ArrayList<>();
        for (String address : localAddresses()) result.add(address + ":" + port);
        return result;
    }

    public static String label(Context context) {
        List<String> addresses = addresses(context);
        if (addresses.isEmpty()) return "未连接局域网";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) builder.append(" / ");
            builder.append(addresses.get(i));
        }
        builder.append("  共享名 ").append(share(context));
        return builder.toString();
    }

    private static List<String> localAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                try {
                    if (!network.isUp() || network.isLoopback()) continue;
                    String name = network.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")
                            || name.startsWith("dummy")) continue;
                } catch (Exception ignored) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    if (!result.contains(host)) result.add(host);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
