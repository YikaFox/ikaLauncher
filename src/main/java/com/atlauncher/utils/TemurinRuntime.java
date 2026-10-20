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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.atlauncher.FileSystem;
import com.atlauncher.Network;
import com.atlauncher.interfaces.NetworkProgressable;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.MultiThreadedDownload;

import okhttp3.Request;
import okhttp3.Response;

/** Temurin JRE download from the TUNA Adoptium mirror. */
public final class TemurinRuntime {
    private static final String MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium";

    private TemurinRuntime() {
    }

    /** Returns false when no Temurin build matches; the caller then falls back to Mojang. */
    /** True when bin/java and the .version marker already exist. */
    public static boolean isInstalled(Path runtimeDirectory, Path versionFile) {
        if (!Files.isRegularFile(versionFile)) {
            return false;
        }

        return Files.isRegularFile(runtimeDirectory.resolve("bin").resolve("java"))
            || Files.isRegularFile(runtimeDirectory.resolve("bin").resolve("java.exe"))
            || Files.isRegularFile(runtimeDirectory.resolve("bin").resolve("javaw.exe"));
    }

    public static boolean download(String mojangVersionName, Path runtimeDirectory, Path versionFile,
            NetworkProgressable progressable, int connections) throws IOException {
        if (isInstalled(runtimeDirectory, versionFile)) {
            LogManager.debug("Java runtime already present at " + runtimeDirectory + ", skipping download");
            return true;
        }

        int majorVersion = getMajorVersion(mojangVersionName);

        if (majorVersion <= 0) {
            LogManager.debug("Unable to determine Java major version for " + mojangVersionName);
            return false;
        }

        String os = getOsName();
        if (os == null) {
            return false;
        }

        String architecture = getArchitecture();
        String extension = "windows".equals(os) ? ".zip" : ".tar.gz";

        // Try a full JRE first, then fall back to a JDK (some Java versions, eg 16,
        // don't have a JRE image published on the mirror but the JDK works just as
        // well to run Minecraft).
        String[] imageTypes = { "jre", "jdk" };
        String listingUrl = null;
        String archiveName = null;

        try {
            for (String imageType : imageTypes) {
                String candidateListingUrl = String.format("%s/%d/%s/%s/%s/", MIRROR, majorVersion, imageType,
                        architecture, os);
                String candidateArchiveName = findLatestArchive(candidateListingUrl, majorVersion, imageType,
                        architecture, os, extension);

                if (candidateArchiveName != null) {
                    listingUrl = candidateListingUrl;
                    archiveName = candidateArchiveName;
                    break;
                }
            }
        } catch (IOException e) {
            LogManager.logStackTrace("Failed to list Temurin builds, falling back to Mojang", e);
            return false;
        }

        if (archiveName == null) {
            LogManager.debug("No Temurin JRE/JDK found for Java " + majorVersion + " on " + os + "/" + architecture);
            return false;
        }

        String downloadUrl = listingUrl + archiveName;
        LogManager.info("Downloading Java " + majorVersion + " runtime from " + downloadUrl);

        Path archive = FileSystem.TEMP.resolve("temurin-jre-" + majorVersion + "-" + architecture + extension);
        Files.createDirectories(FileSystem.TEMP);

        try {
            MultiThreadedDownload.download(downloadUrl, archive, progressable, Math.max(2, connections));

            if (Files.isDirectory(runtimeDirectory)) {
                FileUtils.delete(runtimeDirectory);
            }
            Files.createDirectories(runtimeDirectory);

            if (extension.equals(".zip")) {
                extractZip(archive, runtimeDirectory);
            } else {
                extractTarGz(archive, runtimeDirectory);
            }

            Path javaExecutable = runtimeDirectory.resolve("bin").resolve(OS.isWindows() ? "java.exe" : "java");
            if (!Files.exists(javaExecutable)) {
                throw new IOException("Extracted Temurin runtime does not contain " + javaExecutable);
            }

            Files.write(versionFile, mojangVersionName.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            // clean up and let the caller fall back to Mojang
            LogManager.logStackTrace("Failed to download Temurin Java runtime, falling back to Mojang", e);
            if (Files.isDirectory(runtimeDirectory)) {
                FileUtils.delete(runtimeDirectory);
            }
            return false;
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException e) {
                LogManager.debug("Failed to delete downloaded archive " + archive);
            }
        }
    }

    /** "17.0.15" -> 17, "1.8.0_51" -> 8, "8u51-..." -> 8. */
    public static int getMajorVersion(String versionName) {
        if (versionName == null || versionName.isEmpty()) {
            return -1;
        }

        String name = versionName.trim();

        try {
            if (name.startsWith("1.")) {
                int end = 2;
                while (end < name.length() && Character.isDigit(name.charAt(end))) {
                    end++;
                }
                return Integer.parseInt(name.substring(2, end));
            }

            int end = 0;
            while (end < name.length() && Character.isDigit(name.charAt(end))) {
                end++;
            }

            if (end == 0) {
                return -1;
            }

            return Integer.parseInt(name.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String findLatestArchive(String listingUrl, int majorVersion, String imageType,
            String architecture, String os, String extension) throws IOException {
        String html = fetchText(listingUrl);

        if (html == null) {
            return null;
        }

        Pattern pattern = Pattern.compile("OpenJDK\\d+U-" + Pattern.quote(imageType) + "_"
                + Pattern.quote(architecture) + "_" + Pattern.quote(os)
                + "_hotspot_([0-9][^\"<>/\\\\]*?)\\" + extension);
        Matcher matcher = pattern.matcher(html);

        List<String> versions = new ArrayList<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }

        if (versions.isEmpty()) {
            return null;
        }

        versions.sort(Comparator.comparing(TemurinRuntime::versionKey));
        String latest = versions.get(versions.size() - 1);

        return "OpenJDK" + majorVersion + "U-" + imageType + "_" + architecture + "_" + os + "_hotspot_" + latest
                + extension;
    }

    private static String versionKey(String version) {
        String[] parts = version.split("[^0-9]");
        StringBuilder key = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }

            key.append(String.format("%05d", Integer.parseInt(part)));
        }

        return key.toString();
    }

    private static String fetchText(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();

        try (Response response = Network.CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                LogManager.debug("Failed to list Temurin builds at " + url + ": HTTP " + response.code());
                return null;
            }

            return response.body().string();
        }
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String relativePath = stripRootDirectory(entry.getName());
                if (relativePath.isEmpty()) {
                    continue;
                }

                Path target = safeResolve(destination, relativePath);
                Files.createDirectories(target.getParent());

                try (OutputStream out = Files.newOutputStream(target)) {
                    copy(zipInputStream, out);
                }
            }
        }

        makeExecutablesExecutable(destination);
    }

    private static void extractTarGz(Path archive, Path destination) throws IOException {
        try (TarArchiveInputStream tarInputStream = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(archive))))) {
            TarArchiveEntry entry;

            while ((entry = tarInputStream.getNextEntry()) != null) {
                String relativePath = stripRootDirectory(entry.getName());
                if (relativePath.isEmpty()) {
                    continue;
                }

                Path target = safeResolve(destination, relativePath);

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isSymbolicLink()) {
                    try {
                        Files.createDirectories(target.getParent());
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, Paths.get(entry.getLinkName()));
                    } catch (UnsupportedOperationException | IOException e) {
                        LogManager.debug("Failed to create symlink " + target);
                    }
                } else {
                    Files.createDirectories(target.getParent());

                    try (OutputStream out = Files.newOutputStream(target)) {
                        copy(tarInputStream, out);
                    }
                }
            }
        }

        makeExecutablesExecutable(destination);
    }

    private static String stripRootDirectory(String entryName) {
        String normalized = entryName.replace('\\', '/');
        int index = normalized.indexOf('/');

        if (index == -1) {
            return "";
        }

        return normalized.substring(index + 1);
    }

    private static Path safeResolve(Path destination, String relativePath) throws IOException {
        Path target = destination.resolve(relativePath).normalize();

        if (!target.startsWith(destination.normalize())) {
            throw new IOException("Archive entry outside of destination: " + relativePath);
        }

        return target;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void makeExecutablesExecutable(Path runtimeDirectory) {
        if (OS.isWindows()) {
            return;
        }

        Path binDirectory = runtimeDirectory.resolve("bin");
        if (!Files.isDirectory(binDirectory)) {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(binDirectory)) {
            stream.forEach(path -> {
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                    permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    permissions.add(PosixFilePermission.GROUP_EXECUTE);
                    permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(path, EnumSet.copyOf(permissions));
                } catch (IOException | UnsupportedOperationException e) {
                    LogManager.debug("Failed to make " + path + " executable");
                }
            });
        } catch (IOException e) {
            LogManager.debug("Failed to list " + binDirectory);
        }
    }

    private static String getOsName() {
        if (OS.isWindows()) {
            return "windows";
        }

        if (OS.isMac()) {
            return "mac";
        }

        if (OS.isLinux()) {
            return "linux";
        }

        return null;
    }

    private static String getArchitecture() {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);

        if (architecture.contains("aarch64") || architecture.contains("arm64")) {
            return "aarch64";
        }

        if (architecture.contains("64")) {
            return "x64";
        }

        return "x86";
    }
}
