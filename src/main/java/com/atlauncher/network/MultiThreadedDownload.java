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
package com.atlauncher.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.atlauncher.Network;
import com.atlauncher.interfaces.NetworkProgressable;
import com.atlauncher.managers.LogManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Parallel HTTP range download, falls back to a single stream. */
public final class MultiThreadedDownload {
    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONNECTIONS = 16;
    private static final long MIN_SIZE_FOR_MULTI_THREAD = 4L * 1024L * 1024L;

    private MultiThreadedDownload() {
    }


    public static void download(String url, Path destination, NetworkProgressable progressable, int connections)
            throws IOException {
        download(url, destination, Network.CLIENT, progressable, connections);
    }

    /** Same, but with the given client so progress interceptors still run. */
    public static void download(String url, Path destination, OkHttpClient httpClient,
            NetworkProgressable progressable, int connections) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }

        long contentLength = -1;
        boolean rangeSupported = false;

        Request headRequest = new Request.Builder().url(url).head().build();
        try (Response response = httpClient.newCall(headRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get size of " + url + ": HTTP " + response.code());
            }

            contentLength = parseLength(response.header("Content-Length"));

            String acceptRanges = response.header("Accept-Ranges");
            rangeSupported = acceptRanges != null && acceptRanges.toLowerCase(Locale.ENGLISH).contains("bytes");
        }

        if (!rangeSupported || contentLength <= 0 || connections < 2
                || contentLength < MIN_SIZE_FOR_MULTI_THREAD) {
            singleStreamDownload(url, destination, httpClient, progressable);
            return;
        }

        if (progressable != null) {
            progressable.setTotalBytes(contentLength);
        }

        int threadCount = Math.min(connections, MAX_CONNECTIONS);
        long chunkSize = (long) Math.ceil((double) contentLength / threadCount);

        Path partsDirectory = destination.resolveSibling(destination.getFileName() + ".parts");
        Files.createDirectories(partsDirectory);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final long start = i * chunkSize;
                final long end = Math.min(contentLength - 1, start + chunkSize - 1);

                if (start > end) {
                    break;
                }

                final Path partFile = partsDirectory.resolve("part-" + i);
                futures.add(executor.submit(() -> {
                    downloadRange(url, partFile, start, end, httpClient, progressable);
                    return null;
                }));
            }
        } finally {
            executor.shutdown();
        }

        try {
            if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
                throw new IOException("Timed out downloading " + url);
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    throw new IOException("Failed to download a part of " + url, e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }

        try (OutputStream out = Files.newOutputStream(destination)) {
            for (int i = 0; i < threadCount; i++) {
                Path partFile = partsDirectory.resolve("part-" + i);
                if (Files.exists(partFile)) {
                    Files.copy(partFile, out);
                }
            }
        }

        try {
            for (int i = 0; i < threadCount; i++) {
                Files.deleteIfExists(partsDirectory.resolve("part-" + i));
            }
            Files.deleteIfExists(partsDirectory);
        } catch (IOException e) {
            LogManager.debug("Failed to clean up download parts in " + partsDirectory);
        }
    }

    private static void downloadRange(String url, Path destination, long start, long end, OkHttpClient httpClient,
            NetworkProgressable progressable) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Files.deleteIfExists(destination);

                Request request = new Request.Builder().url(url)
                        .header("Range", "bytes=" + start + "-" + end).build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() != 206) {
                        throw new IOException("Server did not honour the range request: HTTP " + response.code());
                    }

                    try (InputStream in = response.body().byteStream();
                            OutputStream out = Files.newOutputStream(destination)) {
                        copy(in, out, progressable);
                    }
                }

                return;
            } catch (IOException e) {
                lastException = e;
                LogManager.debug("Download attempt " + attempt + " failed for range " + start + "-" + end + ": "
                        + e.getMessage());
            }
        }

        throw lastException;
    }

    private static void singleStreamDownload(String url, Path destination, OkHttpClient httpClient,
            NetworkProgressable progressable) throws IOException {
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download " + url + ": HTTP " + response.code());
            }

            long contentLength = parseLength(response.header("Content-Length"));
            if (progressable != null && contentLength > 0) {
                progressable.setTotalBytes(contentLength);
            }

            try (InputStream in = response.body().byteStream();
                    OutputStream out = Files.newOutputStream(destination)) {
                copy(in, out, progressable);
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, NetworkProgressable progressable) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);

            if (progressable != null) {
                progressable.addDownloadedBytes(read);
            }
        }
    }

    private static long parseLength(String value) {
        if (value == null) {
            return -1;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
