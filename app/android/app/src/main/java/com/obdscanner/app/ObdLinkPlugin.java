package com.obdscanner.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * One serial-style link to an ELM327 adapter over Bluetooth LE, Bluetooth Classic (SPP) or Wi-Fi.
 * The web page drives it through native-shim.js; bytes cross the bridge as latin-1 strings.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
@CapacitorPlugin(
    name = "ObdLink",
    permissions = {
        @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = "loc", strings = { Manifest.permission.ACCESS_FINE_LOCATION })
    }
)
public class ObdLinkPlugin extends Plugin {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final Pattern OBD_NAME = Pattern.compile("OBD|ELM|LINK|VGATE|VEEPEAK|ICAR|SCAN|KONNWEI|CARISTA", Pattern.CASE_INSENSITIVE);
    private static final String CANCELLED = "User cancelled";

    /** Known ELM327 BLE layouts: service, notify characteristic, write characteristic. Same table as the web page. */
    private static final String[][] KNOWN = {
        { "fff0", "fff1", "fff2" },
        { "ffe0", "ffe1", "ffe1" },
        { "18f0", "2af0", "2af1" },
        { "e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f" }
    };

    private interface Link {
        int sid();
        void write(byte[] data, PluginCall call);
        void close();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Link link;
    /** Bumped on every connect/disconnect so a slow connect attempt can tell it was superseded. */
    private int generation;
    /** Set when the user declines the "turn on Bluetooth" prompt, so automatic reconnects do not keep asking. */
    private boolean enableDeclined;
    /** True while the "turn on Bluetooth" prompt is on screen, so a second call does not stack another one. */
    private boolean enableAsking;
    /** Value of generation when the prompt was shown; a connect that was superseded meanwhile is dropped. */
    private int enableGen;

    // ---------------------------------------------------------------- helpers

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private static UUID uuid(String s) {
        return UUID.fromString(s.length() == 4 ? "0000" + s + "-0000-1000-8000-00805f9b34fb" : s);
    }

    /** Runtime permission needed for this kind of link, or null if none. */
    private String neededAlias(String kind) {
        if ("wifi".equals(kind)) return null;
        if (Build.VERSION.SDK_INT >= 31) return "bt";
        return "ble".equals(kind) ? "loc" : null;
    }

    /** Returns true if permissions are in place; otherwise starts the request and the call resumes in permsCallback. */
    private boolean ensurePerms(PluginCall call) {
        String alias = neededAlias(call.getString("kind", ""));
        if (alias == null || getPermissionState(alias) == PermissionState.GRANTED) return true;
        requestPermissionForAlias(alias, call, "permsCallback");
        return false;
    }

    @PermissionCallback
    private void permsCallback(PluginCall call) {
        String alias = neededAlias(call.getString("kind", ""));
        if (alias != null && getPermissionState(alias) != PermissionState.GRANTED) {
            call.reject(
                "loc".equals(alias)
                    ? "Location permission is needed to scan for Bluetooth LE adapters on this Android version."
                    : "Bluetooth permission was denied. Allow “Nearby devices” for OBD Scanner in Settings."
            );
            return;
        }
        if ("pick".equals(call.getMethodName())) pick(call);
        else connect(call);
    }

    private static final String BT_OFF = "Bluetooth is turned off. Turn it on and try again.";

    /**
     * Returns true if Bluetooth is on. Otherwise shows the system "turn on Bluetooth?" prompt and the call
     * resumes in enableCallback, or, when asking is not allowed (ask = false), rejects the call.
     */
    private boolean ensureEnabled(PluginCall call, BluetoothAdapter a, boolean ask) {
        if (a.isEnabled()) {
            enableDeclined = false;
            return true;
        }
        if (!ask || enableAsking) {
            call.reject(BT_OFF);
            return false;
        }
        synchronized (lock) {
            enableGen = generation;
        }
        enableAsking = true;
        try {
            startActivityForResult(call, new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "enableCallback");
        } catch (Exception e) {
            enableAsking = false;
            call.reject(BT_OFF);
        }
        return false;
    }

    @ActivityCallback
    private void enableCallback(PluginCall call, ActivityResult result) {
        enableAsking = false;
        if (call == null) return;
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            enableDeclined = true;
            call.reject(BT_OFF);
            return;
        }
        enableDeclined = false;
        if ("pick".equals(call.getMethodName())) {
            pick(call);
            return;
        }
        // connect() closes whatever link is current, so do not re-run one the page has already moved on from.
        boolean stale;
        synchronized (lock) {
            stale = generation != enableGen;
        }
        if (stale) call.reject(CANCELLED);
        else connect(call);
    }

    private void emitData(int sid, byte[] bytes) {
        JSObject o = new JSObject();
        o.put("sid", sid);
        o.put("data", new String(bytes, StandardCharsets.ISO_8859_1));
        notifyListeners("data", o);
    }

    private void emitClosed(Link l, String reason) {
        synchronized (lock) {
            if (link != l) return;
            link = null;
        }
        JSObject o = new JSObject();
        o.put("sid", l.sid());
        o.put("reason", reason);
        notifyListeners("closed", o);
    }

    private void closeCurrent() {
        Link old;
        synchronized (lock) {
            generation++;
            old = link;
            link = null;
        }
        if (old != null) old.close();
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {}
    }

    // ---------------------------------------------------------------- plugin methods

    @PluginMethod
    public void isEnabled(PluginCall call) {
        boolean on = false;
        try {
            BluetoothAdapter a = adapter();
            on = a != null && a.isEnabled();
        } catch (Exception ignored) {}
        JSObject o = new JSObject();
        o.put("enabled", on);
        call.resolve(o);
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        main.post(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    /** Shows a native device chooser. kind = "ble" (live scan) or "classic" (paired devices). Resolves {address, name}. */
    @PluginMethod
    public void pick(PluginCall call) {
        String kind = call.getString("kind", "");
        BluetoothAdapter a = adapter();
        if (a == null) {
            call.reject("This phone has no Bluetooth.");
            return;
        }
        if (!ensurePerms(call)) return;
        // Picking is always a user action, so always offer to turn Bluetooth on.
        if (!ensureEnabled(call, a, true)) return;
        if ("ble".equals(kind)) main.post(() -> pickBle(call, a));
        else main.post(() -> pickClassic(call, a));
    }

    private void resolveDevice(PluginCall call, String address, String name) {
        JSObject o = new JSObject();
        o.put("address", address);
        o.put("name", name);
        call.resolve(o);
    }

    private void pickClassic(PluginCall call, BluetoothAdapter a) {
        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            // Likely OBD adapters first, everything else after.
            List<BluetoothDevice> rest = new ArrayList<>();
            for (BluetoothDevice d : a.getBondedDevices()) {
                String n = d.getName();
                if (n != null && OBD_NAME.matcher(n).find()) devices.add(d);
                else rest.add(d);
            }
            devices.addAll(rest);
        } catch (SecurityException e) {
            call.reject("Bluetooth permission was denied.");
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getActivity());
        b.setOnCancelListener(d -> call.reject(CANCELLED));
        b.setNegativeButton("Cancel", (d, w) -> call.reject(CANCELLED));
        if (devices.isEmpty()) {
            b.setTitle("No paired devices");
            b.setMessage("Pair the adapter in Bluetooth settings first (it usually shows as “OBDII”, PIN 1234 or 0000), then come back and try again.");
            b.setPositiveButton("Bluetooth settings", (d, w) -> {
                call.reject(CANCELLED);
                try {
                    getActivity().startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception ignored) {}
            });
        } else {
            String[] labels = new String[devices.size()];
            for (int i = 0; i < labels.length; i++) {
                BluetoothDevice d = devices.get(i);
                String n = d.getName();
                labels[i] = (n == null || n.isEmpty() ? "Unnamed device" : n) + "\n" + d.getAddress();
            }
            b.setTitle("Choose your paired adapter");
            b.setItems(labels, (d, which) -> {
                BluetoothDevice dev = devices.get(which);
                resolveDevice(call, dev.getAddress(), dev.getName());
            });
        }
        b.show();
    }

    private void pickBle(PluginCall call, BluetoothAdapter a) {
        final BluetoothLeScanner scanner = a.getBluetoothLeScanner();
        if (scanner == null) {
            call.reject("Bluetooth LE scanning is not available. Is Bluetooth on?");
            return;
        }
        final List<String> addresses = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final ArrayAdapter<String> list = new ArrayAdapter<>(getActivity(), android.R.layout.simple_list_item_1);
        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                if (name == null || name.isEmpty() || addresses.contains(d.getAddress())) return;
                addresses.add(d.getAddress());
                names.add(name);
                list.add(name + "\n" + d.getAddress());
            }
        };
        final boolean[] done = { false };
        AlertDialog.Builder b = new AlertDialog.Builder(getActivity());
        b.setTitle("Scanning for adapters…");
        b.setAdapter(list, (d, which) -> {
            done[0] = true;
            resolveDevice(call, addresses.get(which), names.get(which));
        });
        b.setNegativeButton("Cancel", null);
        b.setOnDismissListener(d -> {
            try {
                scanner.stopScan(cb);
            } catch (Exception ignored) {}
            if (!done[0]) call.reject(CANCELLED);
        });
        try {
            scanner.startScan(cb);
        } catch (Exception e) {
            call.reject("Could not start scanning: " + e.getMessage());
            return;
        }
        b.show();
    }

    /** Opens the link. Options: sid, kind ("ble" | "classic" | "wifi"), address, or host + port. */
    @PluginMethod
    public void connect(PluginCall call) {
        final String kind = call.getString("kind", "");
        final int sid = call.getInt("sid", 0);
        if (!ensurePerms(call)) return;
        closeCurrent();
        final int gen;
        synchronized (lock) {
            gen = generation;
        }

        if ("wifi".equals(kind)) {
            final String host = call.getString("host", "192.168.0.10");
            final int port = call.getInt("port", 35000);
            new Thread(() -> connectWifi(call, sid, gen, host, port)).start();
            return;
        }

        BluetoothAdapter a = adapter();
        String address = call.getString("address", "");
        if (a == null) {
            call.reject("This phone has no Bluetooth.");
            return;
        }
        // Also reached by automatic reconnects: ask once, and not again after the user has said no.
        if (!ensureEnabled(call, a, !enableDeclined)) return;
        final BluetoothDevice dev;
        try {
            dev = a.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            call.reject("Invalid Bluetooth address.");
            return;
        }
        if ("ble".equals(kind)) {
            main.post(() -> {
                BleLink l = new BleLink(sid, call);
                synchronized (lock) {
                    if (gen != generation) {
                        call.reject(CANCELLED);
                        return;
                    }
                    link = l;
                }
                l.start(dev);
            });
        } else {
            new Thread(() -> connectClassic(call, sid, gen, a, dev)).start();
        }
    }

    @PluginMethod
    public void write(PluginCall call) {
        Link l;
        synchronized (lock) {
            l = link;
        }
        if (l == null || l.sid() != call.getInt("sid", 0)) {
            call.reject("Not connected");
            return;
        }
        l.write(call.getString("data", "").getBytes(StandardCharsets.ISO_8859_1), call);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        Integer sid = call.getInt("sid");
        boolean matches;
        synchronized (lock) {
            // A link that is still connecting has not been published yet, so "no link" also means "cancel the attempt".
            matches = sid == null || link == null || link.sid() == sid;
        }
        if (matches) closeCurrent();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        closeCurrent();
    }

    // ---------------------------------------------------------------- Bluetooth Classic + Wi-Fi (stream sockets)

    private void connectClassic(PluginCall call, int sid, int gen, BluetoothAdapter a, BluetoothDevice dev) {
        try {
            a.cancelDiscovery();
        } catch (Exception ignored) {}
        BluetoothSocket sock = null;
        Exception last = null;
        // Cheap ELM327 clones are picky: try secure SPP, then insecure SPP, then raw RFCOMM channel 1.
        for (int attempt = 0; attempt < 3 && sock == null; attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt == 0) s = dev.createRfcommSocketToServiceRecord(SPP);
                else if (attempt == 1) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                s.connect();
                sock = s;
            } catch (Exception e) {
                last = e;
                closeQuietly(s);
            }
            synchronized (lock) {
                if (gen != generation) {
                    closeQuietly(sock);
                    call.reject(CANCELLED);
                    return;
                }
            }
        }
        if (sock == null) {
            call.reject("Could not open the Bluetooth link: " + (last == null ? "unknown error" : last.getMessage()));
            return;
        }
        try {
            publishStream(call, sid, gen, sock, sock.getInputStream(), sock.getOutputStream());
        } catch (IOException e) {
            closeQuietly(sock);
            call.reject("Could not open the Bluetooth link: " + e.getMessage());
        }
    }

    private void connectWifi(PluginCall call, int sid, int gen, String host, int port) {
        Socket sock = null;
        try {
            sock = wifiSocket();
            sock.connect(new InetSocketAddress(host, port), 6000);
            sock.setTcpNoDelay(true);
            publishStream(call, sid, gen, sock, sock.getInputStream(), sock.getOutputStream());
        } catch (Exception e) {
            closeQuietly(sock);
            call.reject(
                "Could not reach the Wi‑Fi adapter at " + host + ":" + port +
                ". Join the adapter’s Wi‑Fi network in phone Settings, check the address, and try again."
            );
        }
    }

    /** A socket bound to the Wi-Fi network, so Android does not route it over mobile data when the adapter has no internet. */
    private Socket wifiSocket() throws IOException {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return n.getSocketFactory().createSocket();
                    }
                }
            }
        } catch (SecurityException ignored) {}
        return new Socket();
    }

    private void publishStream(PluginCall call, int sid, int gen, Closeable sock, InputStream in, OutputStream out) {
        StreamLink l = new StreamLink(sid, sock, in, out);
        synchronized (lock) {
            if (gen != generation) {
                closeQuietly(sock);
                call.reject(CANCELLED);
                return;
            }
            link = l;
        }
        call.resolve();
        l.start();
    }

    private class StreamLink implements Link {
        private final int sid;
        private final Closeable sock;
        private final InputStream in;
        private final OutputStream out;
        private final ExecutorService writer = Executors.newSingleThreadExecutor();
        private volatile boolean closed;

        StreamLink(int sid, Closeable sock, InputStream in, OutputStream out) {
            this.sid = sid;
            this.sock = sock;
            this.in = in;
            this.out = out;
        }

        void start() {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[1024];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) emitData(sid, Arrays.copyOf(buf, n));
                } catch (IOException ignored) {}
                if (!closed) {
                    close();
                    emitClosed(this, "Adapter link closed");
                }
            }, "obd-read");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public int sid() {
            return sid;
        }

        @Override
        public void write(byte[] data, PluginCall call) {
            if (closed) {
                call.reject("Not connected");
                return;
            }
            try {
                writer.execute(() -> {
                    try {
                        out.write(data);
                        out.flush();
                        call.resolve();
                    } catch (IOException e) {
                        call.reject("Write failed: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                call.reject("Not connected");
            }
        }

        @Override
        public void close() {
            closed = true;
            writer.shutdown();
            closeQuietly(sock);
        }
    }

    // ---------------------------------------------------------------- Bluetooth LE

    private class BleLink extends BluetoothGattCallback implements Link {
        private final int sid;
        private PluginCall connectCall;
        private PluginCall pendingWrite;
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic writeChar;
        private boolean noResponse;
        private boolean ready;
        private boolean closed;
        private final Runnable timeout = () -> failConnect("Timed out connecting to the adapter. Check the ignition is on and no other app is using it.");

        BleLink(int sid, PluginCall connectCall) {
            this.sid = sid;
            this.connectCall = connectCall;
        }

        void start(BluetoothDevice dev) {
            main.postDelayed(timeout, 15000);
            try {
                gatt = dev.connectGatt(getContext(), false, this, BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) {
                failConnect("Could not connect: " + e.getMessage());
                return;
            }
            if (gatt == null) failConnect("Could not connect to the Bluetooth LE adapter.");
        }

        @Override
        public int sid() {
            return sid;
        }

        private synchronized void failConnect(String message) {
            main.removeCallbacks(timeout);
            if (ready || closed) return;
            PluginCall c = connectCall;
            connectCall = null;
            shutdown();
            synchronized (lock) {
                if (link == this) link = null;
            }
            if (c != null) c.reject(message);
        }

        private void shutdown() {
            closed = true;
            main.removeCallbacks(timeout);
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (Exception ignored) {}
            }
            PluginCall w = pendingWrite;
            pendingWrite = null;
            if (w != null) w.reject("Not connected");
        }

        @Override
        public synchronized void close() {
            PluginCall c = connectCall;
            connectCall = null;
            shutdown();
            if (c != null) c.reject(CANCELLED);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Some adapters drop the link if discovery starts in the same tick as the connect.
                main.postDelayed(() -> {
                    boolean ok;
                    synchronized (this) {
                        if (closed) return;
                        try {
                            ok = g.discoverServices();
                        } catch (Exception e) {
                            ok = false;
                        }
                    }
                    if (!ok) failConnect("Could not read the adapter’s services.");
                }, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasReady;
                synchronized (this) {
                    if (closed) return;
                    wasReady = ready;
                }
                if (!wasReady) {
                    failConnect("Could not connect to the Bluetooth LE adapter (status " + status + ").");
                } else {
                    synchronized (this) {
                        shutdown();
                    }
                    emitClosed(this, "Bluetooth device disconnected");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattCharacteristic notify = null, write = null;
            for (String[] k : KNOWN) {
                BluetoothGattService s = g.getService(uuid(k[0]));
                if (s == null) continue;
                BluetoothGattCharacteristic n = s.getCharacteristic(uuid(k[1]));
                BluetoothGattCharacteristic w = s.getCharacteristic(uuid(k[2]));
                if (n != null && w != null && canNotify(n) && canWrite(w)) {
                    notify = n;
                    write = w;
                    break;
                }
            }
            if (notify == null) {
                for (BluetoothGattService s : g.getServices()) {
                    BluetoothGattCharacteristic n = null, w = null;
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        if (n == null && canNotify(c)) n = c;
                        if (w == null && canWrite(c)) w = c;
                    }
                    if (n != null && w != null) {
                        notify = n;
                        write = w;
                        break;
                    }
                }
            }
            if (notify == null || write == null) {
                failConnect("No serial service found on this device. Is it an ELM327 BLE adapter?");
                return;
            }
            boolean subscribed = true;
            boolean waitForDescriptor = false;
            synchronized (this) {
                if (closed) return;
                writeChar = write;
                noResponse = (write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                try {
                    g.setCharacteristicNotification(notify, true);
                    BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
                    if (d != null) {
                        boolean notifies = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        d.setValue(notifies ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        subscribed = g.writeDescriptor(d);
                        waitForDescriptor = subscribed;
                    }
                } catch (Exception e) {
                    subscribed = false;
                }
            }
            if (!subscribed) failConnect("Could not subscribe to the adapter’s data.");
            else if (!waitForDescriptor) markReady();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (!CCCD.equals(d.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) markReady();
            else failConnect("Could not subscribe to the adapter’s data (status " + status + ").");
        }

        private void markReady() {
            PluginCall c;
            synchronized (this) {
                if (closed || ready) return;
                ready = true;
                main.removeCallbacks(timeout);
                c = connectCall;
                connectCall = null;
            }
            if (c != null) c.resolve();
        }

        private boolean canNotify(BluetoothGattCharacteristic c) {
            return (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
        }

        private boolean canWrite(BluetoothGattCharacteristic c) {
            return (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null && v.length > 0 && !closed) emitData(sid, v.clone());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            PluginCall w;
            synchronized (this) {
                w = pendingWrite;
                pendingWrite = null;
            }
            if (w == null) return;
            if (status == BluetoothGatt.GATT_SUCCESS) w.resolve();
            else w.reject("Write failed (status " + status + ")");
        }

        @Override
        public synchronized void write(byte[] data, PluginCall call) {
            if (closed || !ready || writeChar == null) {
                call.reject("Not connected");
                return;
            }
            if (pendingWrite != null) {
                call.reject("Previous write still in progress");
                return;
            }
            try {
                writeChar.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                writeChar.setValue(data);
                pendingWrite = call;
                if (!gatt.writeCharacteristic(writeChar)) {
                    pendingWrite = null;
                    call.reject("Write failed");
                }
            } catch (Exception e) {
                pendingWrite = null;
                call.reject("Write failed: " + e.getMessage());
            }
        }
    }
}
