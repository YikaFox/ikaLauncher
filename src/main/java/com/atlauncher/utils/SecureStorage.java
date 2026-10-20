/*
 * ikaLauncher - https://github.com/YikaFox/ikaLauncher
 * Copyright (C) 2026 YikaFox
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.sun.jna.platform.win32.Crypt32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinCrypt;
import com.sun.jna.ptr.PointerByReference;

/** DPAPI on Windows (user bound), AES-GCM elsewhere. */
public final class SecureStorage {
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final byte[] KEY_FILE_MAGIC = new byte[] { 'i', 'k', 'a', 'K', 'E', 'Y', '1' };

    private SecureStorage() {
    }

    public static byte[] encrypt(byte[] plaintext) throws Exception {
        if (OS.isWindows()) {
            return dpapiProtect(plaintext);
        }

        byte[] key = getOrCreateKey();
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] ciphertext = cipher.doFinal(plaintext);
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    public static byte[] decrypt(byte[] data) throws Exception {
        if (OS.isWindows()) {
            return dpapiUnprotect(data);
        }

        if (data.length <= GCM_IV_LENGTH) {
            throw new IOException("Encrypted data is too short");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, iv.length);

        byte[] ciphertext = new byte[data.length - iv.length];
        System.arraycopy(data, iv.length, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getOrCreateKey(), "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        return cipher.doFinal(ciphertext);
    }

    private static byte[] dpapiProtect(byte[] data) throws IOException {
        WinCrypt.DATA_BLOB input = new WinCrypt.DATA_BLOB(data);
        WinCrypt.DATA_BLOB output = new WinCrypt.DATA_BLOB();

        if (!Crypt32.INSTANCE.CryptProtectData(input, "ikaLauncher account data", null, null, null, 0, output)) {
            throw new IOException("CryptProtectData failed with error " + Kernel32.INSTANCE.GetLastError());
        }

        try {
            return output.getData();
        } finally {
            Kernel32.INSTANCE.LocalFree(output.pbData);
        }
    }

    private static byte[] dpapiUnprotect(byte[] data) throws IOException {
        WinCrypt.DATA_BLOB input = new WinCrypt.DATA_BLOB(data);
        WinCrypt.DATA_BLOB output = new WinCrypt.DATA_BLOB();
        PointerByReference description = new PointerByReference();

        if (!Crypt32.INSTANCE.CryptUnprotectData(input, description, null, null, null, 0, output)) {
            throw new IOException("CryptUnprotectData failed with error " + Kernel32.INSTANCE.GetLastError());
        }

        try {
            return output.getData();
        } finally {
            Kernel32.INSTANCE.LocalFree(output.pbData);
        }
    }

    private static byte[] getOrCreateKey() throws IOException {
        Path keyFile = getAccountsFile().resolveSibling("accounts.key");

        if (Files.isRegularFile(keyFile)) {
            byte[] contents = Files.readAllBytes(keyFile);

            if (contents.length == KEY_FILE_MAGIC.length + 32) {
                boolean valid = true;
                for (int i = 0; i < KEY_FILE_MAGIC.length; i++) {
                    if (contents[i] != KEY_FILE_MAGIC[i]) {
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    byte[] key = new byte[32];
                    System.arraycopy(contents, KEY_FILE_MAGIC.length, key, 0, 32);
                    return key;
                }
            }

            throw new IOException("Invalid key file " + keyFile);
        }

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);

        ByteBuffer buffer = ByteBuffer.allocate(KEY_FILE_MAGIC.length + key.length);
        buffer.put(KEY_FILE_MAGIC);
        buffer.put(key);

        if (keyFile.getParent() != null) {
            Files.createDirectories(keyFile.getParent());
        }
        Files.write(keyFile, buffer.array());
        restrictPermissions(keyFile);

        return key;
    }

    private static void restrictPermissions(Path path) {
        if (OS.isWindows()) {
            return;
        }

        try {
            Files.setPosixFilePermissions(path,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort only
        }
    }

    private static Path getAccountsFile() {
        return com.atlauncher.FileSystem.ACCOUNTS;
    }
}
