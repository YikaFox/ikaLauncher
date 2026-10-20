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

import com.atlauncher.managers.LogManager;

/**
 * Sets sun.java2d.uiScale before AWT starts, based on the Windows DPI setting.
 */
public final class UiScale {
    private UiScale() {
    }

    public static void configure() {
        if (!OS.isWindows()) {
            return;
        }

        // Respect an explicit -Dsun.java2d.uiScale=... from the user or launcher.
        if (System.getProperty("sun.java2d.uiScale") != null) {
            return;
        }

        try {
            int dpi = com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Control Panel\\Desktop\\WindowMetrics", "AppliedDPI");

            if (dpi <= 0) {
                dpi = 96;
            }

            // Follow the Windows scaling but only in discrete steps, and make sure the
            // UI still fits on screen. Fractional scales get antialiased text so the
            // pixel font doesn't look broken, integer scales stay perfectly crisp.
            double scale = Math.max(1.0, Math.min(3.0, Math.round((dpi / 96.0) * 100.0) / 100.0));

            try {
                int screenWidth = com.sun.jna.platform.win32.User32.INSTANCE.GetSystemMetrics(0);
                int screenHeight = com.sun.jna.platform.win32.User32.INSTANCE.GetSystemMetrics(1);

                while (scale > 1.0 && (screenWidth / scale < 1280 || screenHeight / scale < 720)) {
                    scale = scale <= 1.25 ? 1.0 : scale - 0.25;
                }
            } catch (Throwable ignored) {
                // keep the DPI based scale
            }

            boolean integerScale = Math.abs(scale - Math.rint(scale)) < 0.001;

            // Pixel fonts are only crisp at integer sizes; let Java antialias the text for
            // fractional scales so it stays readable instead of looking smeared.
            System.setProperty("awt.useSystemAAFontSettings", integerScale ? "off" : "on");
            System.setProperty("swing.aatext", integerScale ? "false" : "true");

            System.setProperty("sun.java2d.uiScale", scale == Math.rint(scale)
                    ? Integer.toString((int) scale)
                    : Double.toString(scale));
            LogManager.debug("Setting integer UI scale of " + scale + " (Windows DPI " + dpi + ")");
        } catch (Throwable t) {
            // Registry value missing/blocked: leave Java's default scaling in place.
            LogManager.debug("Unable to determine Windows DPI, using default UI scaling");
        }
    }
}
