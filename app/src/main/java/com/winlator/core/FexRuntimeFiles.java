package com.winlator.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Integrity and repair of the exact FEX 2609 package used by the Battle.net launcher. */
public final class FexRuntimeFiles {
    private FexRuntimeFiles() {}

    private static final class Entry {
        final String path;
        final long size;
        final String sha256;
        Entry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    // Derived from the checksum-pinned FEXCore-2609.wcp, not from installed files.
    private static final Entry[] PACKAGE_FILES = {
        new Entry("profile.json", 534, "76881d8f8322e92a4d44a6e6a4f6c87e6d1874e5aef067e1b7f71eeb0f814dc8"),
        new Entry("system32/libwow64fex.dll", 5308416, "40263be61633929331fc7c613ea3b0cf095251d403f3433df325c5d299989f0f"),
        new Entry("system32/libarm64ecfex.dll", 5910528, "ecbcd1c294a857216ad532661c97a60878021e206feca27d2a96e06bda930cb2"),
        new Entry("aarch64-unix/libwow64fex.so", 113184, "b9ef34adaf06c2b0ecfa74e775688e1d4097bb3ddfdb18347d065902f241ad14"),
        new Entry("aarch64-unix/libarm64ecfex.so", 113184, "b9ef34adaf06c2b0ecfa74e775688e1d4097bb3ddfdb18347d065902f241ad14"),
    };

    public static boolean isPackageValid(File directory) {
        for (Entry entry : PACKAGE_FILES) {
            if (!matches(new File(directory, entry.path), entry.size, entry.sha256)) return false;
        }
        return true;
    }

    /** Recheck installed copies even when a previous applied-version marker exists. */
    public static void install(File packageDirectory, File system32Directory, File unixDirectory) throws IOException {
        if (!isPackageValid(packageDirectory)) throw new IOException("FEX 2609 package is incomplete or corrupt");
        for (Entry entry : PACKAGE_FILES) {
            if (entry.path.equals("profile.json")) continue;
            File directory = entry.path.startsWith("system32/") ? system32Directory : unixDirectory;
            File target = new File(directory, new File(entry.path).getName());
            copyVerifiedFile(new File(packageDirectory, entry.path), target, entry.size, entry.sha256);
        }
    }

    static boolean matches(File file, long size, String expectedHash) {
        if (!file.isFile() || file.length() != size) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value & 0xff) >>> 4, 16));
                hash.append(Character.forDigit(value & 0x0f, 16));
            }
            return expectedHash.contentEquals(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    static void copyVerifiedFile(File source, File target, long size, String hash) throws IOException {
        if (!matches(source, size, hash)) throw new IOException("Invalid FEX source: " + source.getName());
        if (matches(target, size, hash)) return;
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("Couldn't create FEX runtime directory");
        File temporary = File.createTempFile(target.getName(), ".part", parent);
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (!matches(temporary, size, hash)) throw new IOException("Incomplete FEX copy: " + source.getName());
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
