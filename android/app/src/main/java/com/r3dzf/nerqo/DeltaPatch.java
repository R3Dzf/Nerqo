package com.r3dzf.nerqo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/** Native Java reader for Python's NRQDEL01 delta format. No native libraries. */
public final class DeltaPatch {
    private static final int CHUNK = 1024 * 1024;
    private static final long LIMIT = 8L * 1024 * 1024 * 1024;
    private static final byte[] MAGIC = "NRQDEL01".getBytes(StandardCharsets.US_ASCII);
    private DeltaPatch() {}

    private static MessageDigest digest() throws IOException {
        try {return MessageDigest.getInstance("SHA-256");}
        catch (NoSuchAlgorithmException e) {throw new IOException(e);}
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for(byte b : bytes) out.append(String.format(java.util.Locale.US,"%02x",b & 255));
        return out.toString();
    }
    public static String sha256(File file) throws IOException {
        MessageDigest sha = digest();
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) >= 0) if(count > 0) sha.update(buffer, 0, count);
        }
        return hex(sha.digest());
    }
    public static void apply(File base, File patch, File result) throws IOException {
        if (result.exists()) throw new IOException("Output already exists");
        boolean started = false;
        try (RandomAccessFile old = new RandomAccessFile(base,"r");
             DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(patch)))) {
            byte[] signature = new byte[8];in.readFully(signature);
            long oldSize = in.readLong(), resultSize = in.readLong();
            byte[] oldHash = new byte[32], resultHash = new byte[32];
            in.readFully(oldHash);in.readFully(resultHash);
            if(!Arrays.equals(MAGIC,signature) || oldSize < 0 || resultSize < 0 ||
                    oldSize > LIMIT || resultSize > LIMIT) throw new IOException("Invalid patch header");
            if(base.length() != oldSize || !sha256(base).equals(hex(oldHash)))
                throw new IOException("Installed APK does not match this delta");
            MessageDigest sha = digest();long written = 0;
            byte[] scratch = new byte[CHUNK];
            try(OutputStream out = new BufferedOutputStream(new FileOutputStream(result))) {
                started = true;
                while (true) {
                    int op = in.readUnsignedByte();
                    if(op == 'E') {
                        if(in.read() != -1) throw new IOException("Trailing data");
                        break;
                    }
                    if(op == 'C') {
                        long offset = in.readLong();long remaining = Integer.toUnsignedLong(in.readInt());
                        if(offset < 0 || remaining == 0 || offset > oldSize || remaining > oldSize - offset)
                            throw new IOException("Invalid copy operation");
                        old.seek(offset);
                        while(remaining > 0) {
                            int count = (int)Math.min(remaining, scratch.length);
                            old.readFully(scratch,0,count);out.write(scratch,0,count);sha.update(scratch,0,count);
                            written += count; remaining -= count;
                        }
                    } else if(op == 'D') {
                        long rawSize = Integer.toUnsignedLong(in.readInt());
                        long packedSize = Integer.toUnsignedLong(in.readInt());
                        if(rawSize == 0 || rawSize > CHUNK || packedSize == 0 || packedSize > CHUNK + 1024)
                            throw new IOException("Oversized literal");
                        byte[] packed = new byte[(int)packedSize];in.readFully(packed);
                        try(InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(packed))) {
                            int remaining = (int)rawSize;
                            while(remaining > 0) {
                                int count = inflater.read(scratch,0,Math.min(scratch.length,remaining));
                                if(count < 0) throw new IOException("Truncated literal");
                                if(count == 0) continue;
                                out.write(scratch,0,count);sha.update(scratch,0,count);
                                written += count;remaining -= count;
                            }
                            if(inflater.read() != -1) throw new IOException("Extra literal bytes");
                        }
                    } else throw new IOException("Unknown delta opcode");
                    if(written > resultSize) throw new IOException("Output too large");
                }
            }
            if(written != resultSize || !Arrays.equals(sha.digest(),resultHash))
                throw new IOException("Reconstructed APK integrity check failed");
        } catch(Exception e) {
            if(started) result.delete();
            if(e instanceof IOException) throw (IOException)e;
            throw new IOException("Patch failed",e);
        }
    }
}
