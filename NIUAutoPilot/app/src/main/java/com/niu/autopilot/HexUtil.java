package com.niu.autopilot;

public class HexUtil {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        return sb.toString();
    }

    /** Parse a hex string (with or without spaces). Returns null if invalid. */
    public static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String s = hex.replaceAll("[\\s\\-]", "").trim();
        if (s.isEmpty()) return new byte[0];
        if (s.length() % 2 != 0) return null;
        try {
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
