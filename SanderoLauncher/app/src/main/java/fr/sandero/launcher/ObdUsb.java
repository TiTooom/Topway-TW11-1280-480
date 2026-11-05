package fr.sandero.launcher;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Liaison série USB vers un ELM327 USB, sans bibliothèque externe.
 * Puces prises en charge (les plus courantes dans les câbles OBD USB) :
 * FTDI FT232 (0403), CH340/CH341 (1A86), CP210x (10C4), PL2303 (067B), et CDC-ACM standard.
 * Séquences d'initialisation reprises des pilotes Linux / usb-serial-for-android (probable :
 * jamais essayé sur l'autoradio).
 */
final class ObdUsb implements Obd.Link {

    static final String ACTION_PERMISSION = "fr.sandero.launcher.USB_PERMISSION";
    private static final int FTDI = 1, CH34X = 2, CP210X = 3, PL2303 = 4, CDC = 5, OTHER = 6;
    private static final int T = 500;

    private final UsbDeviceConnection conn;
    private final UsbInterface data;
    private final UsbEndpoint in, out;
    private final int chip, ctrlIface;
    private final byte[] rbuf;

    static List<UsbDevice> devices(Context c) {
        List<UsbDevice> l = new ArrayList<>();
        try {
            UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
            if (um != null) l.addAll(um.getDeviceList().values());
        } catch (Throwable e) {
            Journal.error("OBD", "liste USB", e);
        }
        return l;
    }

    static UsbDevice find(Context c, String name) {
        for (UsbDevice d : devices(c)) if (d.getDeviceName().equals(name)) return d;
        return null;
    }

    private static int chipOf(UsbDevice d) {
        switch (d.getVendorId()) {
            case 0x0403: return FTDI;
            case 0x1A86: return CH34X;
            case 0x10C4: return CP210X;
            case 0x067B: return PL2303;
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) return CDC;
        }
        return OTHER;
    }

    static String describe(UsbDevice d) {
        String[] names = {"", "FTDI", "CH340", "CP210x", "PL2303", "CDC série", "inconnu"};
        String prod = "";
        try {
            if (d.getProductName() != null) prod = d.getProductName() + " · ";
        } catch (Throwable ignored) {
        }
        return prod + names[chipOf(d)] + String.format(" (%04X:%04X)", d.getVendorId(), d.getProductId());
    }

    /** Ouvre le périphérique (la permission USB doit déjà être accordée). */
    static ObdUsb open(Context c, String name) throws IOException {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        UsbDevice d = find(c, name);
        if (um == null || d == null) throw new IOException("câble USB non trouvé (débranché ?)");
        if (!um.hasPermission(d)) throw new IOException("permission USB non accordée");
        UsbDeviceConnection conn = um.openDevice(d);
        if (conn == null) throw new IOException("ouverture USB refusée");
        try {
            return new ObdUsb(d, conn);
        } catch (IOException e) {
            conn.close();
            throw e;
        }
    }

    private ObdUsb(UsbDevice d, UsbDeviceConnection conn) throws IOException {
        this.conn = conn;
        chip = chipOf(d);
        UsbInterface dataIf = null, ctrlIf = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && ctrlIf == null) ctrlIf = f;
            if (dataIf == null && bulk(f, UsbConstants.USB_DIR_IN) != null && bulk(f, UsbConstants.USB_DIR_OUT) != null) dataIf = f;
        }
        if (dataIf == null) throw new IOException("aucune liaison série sur ce périphérique USB");
        data = dataIf;
        ctrlIface = ctrlIf != null ? ctrlIf.getId() : 0;
        if (ctrlIf != null && ctrlIf != dataIf) conn.claimInterface(ctrlIf, true);
        if (!conn.claimInterface(data, true)) throw new IOException("interface USB occupée");
        in = bulk(data, UsbConstants.USB_DIR_IN);
        out = bulk(data, UsbConstants.USB_DIR_OUT);
        rbuf = new byte[Math.max(64, in.getMaxPacketSize()) * 4];
        init();
        setBaud(38400);
        Journal.log("OBD", "USB ouvert : " + describe(d));
    }

    private static UsbEndpoint bulk(UsbInterface f, int dir) {
        for (int i = 0; i < f.getEndpointCount(); i++) {
            UsbEndpoint e = f.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == dir) return e;
        }
        return null;
    }

    private int ctl(int type, int req, int value, int index, byte[] buf) {
        return conn.controlTransfer(type, req, value, index, buf, buf == null ? 0 : buf.length, T);
    }

    private void init() {
        switch (chip) {
            case FTDI:
                ctl(0x40, 0, 0, 0, null);          // reset
                ctl(0x40, 2, 0, 0, null);          // pas de contrôle de flux
                ctl(0x40, 4, 8, 0, null);          // 8N1
                ctl(0x40, 1, 0x0303, 0, null);     // DTR + RTS
                ctl(0x40, 9, 16, 0, null);         // latence 16 ms (moins de réveils à vide)
                break;
            case CH34X:
                ctl(0xC0, 0x5F, 0, 0, new byte[2]);
                ctl(0x40, 0xA1, 0, 0, null);
                ctl(0xC0, 0x95, 0x2518, 0, new byte[2]);
                ctl(0x40, 0x9A, 0x2518, 0x00C3, null);   // 8N1
                ctl(0xC0, 0x95, 0x0706, 0, new byte[2]);
                ctl(0x40, 0xA1, 0x501F, 0xD90A, null);
                ctl(0x40, 0xA4, ~(0x20 | 0x40) & 0xFF, 0, null); // DTR + RTS
                break;
            case CP210X:
                ctl(0x41, 0x00, 1, data.getId(), null);       // activer
                ctl(0x41, 0x03, 0x0800, data.getId(), null);  // 8N1
                ctl(0x41, 0x07, 0x0303, data.getId(), null);  // DTR + RTS
                break;
            case PL2303: {
                byte[] b = new byte[1];
                byte[] raw = conn.getRawDescriptors();
                boolean hx = raw == null || raw.length < 8 || raw[7] == 0x40;
                ctl(0xC0, 1, 0x8484, 0, b);
                ctl(0x40, 1, 0x0404, 0, null);
                ctl(0xC0, 1, 0x8484, 0, b);
                ctl(0xC0, 1, 0x8383, 0, b);
                ctl(0xC0, 1, 0x8484, 0, b);
                ctl(0x40, 1, 0x0404, 1, null);
                ctl(0xC0, 1, 0x8484, 0, b);
                ctl(0xC0, 1, 0x8383, 0, b);
                ctl(0x40, 1, 0, 1, null);
                ctl(0x40, 1, 1, 0, null);
                ctl(0x40, 1, 2, hx ? 0x44 : 0x24, null);
                ctl(0x21, 0x22, 0x03, 0, null);
                break;
            }
            default:
                ctl(0x21, 0x22, 0x03, ctrlIface, null);       // CDC : DTR + RTS
        }
    }

    @Override
    public boolean setBaud(int baud) {
        switch (chip) {
            case FTDI: {
                int div8 = Math.round(24000000f / baud);   // diviseur en 1/8
                int[] sub = {0, 3, 2, 4, 1, 5, 6, 7};
                int code = sub[div8 & 7];
                int value = (div8 >> 3) | ((code & 3) << 14);
                ctl(0x40, 3, value, code >> 2, null);
                break;
            }
            case CH34X: {
                long factor = 1532620800L / baud;
                int divisor = 3;
                while (factor > 0xFFF0 && divisor > 0) {
                    factor >>= 3;
                    divisor--;
                }
                factor = 0x10000 - factor;
                divisor |= 0x80;
                ctl(0x40, 0x9A, 0x1312, (int) ((factor & 0xFF00) | divisor), null);
                ctl(0x40, 0x9A, 0x0F2C, (int) (factor & 0xFF), null);
                break;
            }
            case CP210X:
                ctl(0x41, 0x1E, 0, data.getId(), new byte[]{(byte) baud, (byte) (baud >> 8), (byte) (baud >> 16), (byte) (baud >> 24)});
                break;
            default: {
                byte[] lc = {(byte) baud, (byte) (baud >> 8), (byte) (baud >> 16), (byte) (baud >> 24), 0, 0, 8};
                ctl(0x21, 0x20, 0, chip == PL2303 ? 0 : ctrlIface, lc);
            }
        }
        return true;
    }

    @Override
    public void write(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = Math.min(b.length - off, out.getMaxPacketSize());
            byte[] chunk = new byte[n];
            System.arraycopy(b, off, chunk, 0, n);
            int w = conn.bulkTransfer(out, chunk, n, 1000);
            if (w < 0) throw new IOException("écriture USB impossible (câble débranché ?)");
            off += w;
        }
    }

    @Override
    public int read(byte[] b, int timeoutMs) {
        int n = conn.bulkTransfer(in, rbuf, rbuf.length, Math.max(1, timeoutMs));
        if (n <= 0) return 0;
        if (chip != FTDI) {
            n = Math.min(n, b.length);
            System.arraycopy(rbuf, 0, b, 0, n);
            return n;
        }
        // FTDI : 2 octets d'état au début de chaque paquet
        int ps = Math.max(64, in.getMaxPacketSize()), k = 0;
        for (int p = 0; p < n; p += ps) {
            for (int i = p + 2; i < Math.min(n, p + ps) && k < b.length; i++) b[k++] = rbuf[i];
        }
        return k;
    }

    @Override
    public void close() {
        try {
            conn.releaseInterface(data);
        } catch (Throwable ignored) {
        }
        try {
            conn.close();
        } catch (Throwable ignored) {
        }
    }
}
