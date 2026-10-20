/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
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
package com.atlauncher.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.ConsoleState;
import com.atlauncher.evnt.listener.RelocalizationListener;
import com.atlauncher.evnt.manager.ConsoleStateManager;
import com.atlauncher.evnt.manager.RelocalizationManager;
import com.atlauncher.gui.components.Console;
import com.atlauncher.gui.components.ConsoleBottomBar;
import com.atlauncher.managers.LogManager;
import com.atlauncher.utils.Utils;

public class LauncherConsole extends JFrame implements RelocalizationListener {

    private static final long serialVersionUID = -3538990021922025818L;
    public Console console;
    private JScrollPane scrollPane;
    private final ConsoleBottomBar bottomBar;
    private JPopupMenu contextMenu; // Right click menu

    private JMenuItem copy;

    private static final String BANNER = "    _ __         __                           __\n   (_) /______ _/ /   ____ ___  ______  _____/ /_  ___  _____\n  / / //_/ __ `/ /   / __ `/ / / / __ \\/ ___/ __ \\/ _ \\/ ___/\n / / ,< / /_/ / /___/ /_/ / /_/ / / / / /__/ / / /  __/ /\n/_/_/|_|\\__,_/_____/\\__,_/\\__,_/_/ /_/\\___/_/ /_/\\___/_/\n";

    /** Console banner, written directly so it has no timestamp prefix. */
    public void printBanner() {
        String fontFamily = console.getFont().getFamily();
        int fontSize = console.getFont().getSize();

        // monospace so the art lines up
        console.setTextFont(java.awt.Font.MONOSPACED, fontSize);
        console.setColor(UIManager.getColor("Console.LogType.info")).setBold(false);
        console.write("\n" + BANNER);

        console.setTextFont(fontFamily, fontSize);
        console.setColor(UIManager.getColor("EditorPane.foreground")).setBold(false);
        console.write(GetText.tr("Based on ATLauncher · by YikaFox") + "\n");
    }

    public LauncherConsole() {
        // #. {0} is the name of the launcher (ATLauncher)
        setTitle(GetText.tr("{0} Console", Constants.LAUNCHER_NAME));
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setIconImage(Utils.getImage("/assets/image/icon.png"));
        setLayout(new BorderLayout());

        setMinimumSize(new Dimension(400, 200));
        setSize(new Dimension(650, 400));

        try {
            if (App.settings.rememberWindowSizePosition && App.settings.consoleSize != null
                    && App.settings.consolePosition != null) {
                setBounds(App.settings.consolePosition.x, App.settings.consolePosition.y,
                        App.settings.consoleSize.width, App.settings.consoleSize.height);
            }
        } catch (Exception e) {
            LogManager.logStackTrace("Error setting custom remembered window size settings", e);
        }

        console = new Console();

        setupContextMenu(); // Setup the right click menu

        bottomBar = new ConsoleBottomBar();

        scrollPane = new JScrollPane(console, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
        RelocalizationManager.addListener(this);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent evt) {
                Component c = (Component) evt.getSource();

                bottomBar.hideBottomBarIconsIfNeeded();
                setMinimumSize(new Dimension(Math.max(bottomBar.leftSide.getWidth(), 400), 200));

                if (App.settings.rememberWindowSizePosition) {
                    App.settings.consoleSize = c.getSize();
                    App.settings.save();
                }
            }

            @Override
            public void componentMoved(ComponentEvent evt) {
                Component c = (Component) evt.getSource();

                if (App.settings.rememberWindowSizePosition) {
                    App.settings.consolePosition = c.getLocation();
                    App.settings.save();
                }
            }
        });
    }

    @Override
    public void setVisible(boolean flag) {
        super.setVisible(flag);
        bottomBar.hideBottomBarIconsIfNeeded();
        if (flag) {
            ConsoleStateManager.setState(ConsoleState.OPEN);

            // jump to the newest output
            SwingUtilities.invokeLater(() -> {
                console.setCaretPosition(console.getDocument().getLength());
                scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
            });
        } else {
            ConsoleStateManager.setState(ConsoleState.CLOSED);
        }
    }

    private void setupContextMenu() {
        contextMenu = new JPopupMenu();

        copy = new JMenuItem(GetText.tr("Copy"));
        copy.addActionListener(e -> {
            StringSelection text = new StringSelection(console.getSelectedText());
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(text, null);
        });
        contextMenu.add(copy);

        console.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (console.getSelectedText() != null) {
                    if (e.getButton() == MouseEvent.BUTTON3) {
                        contextMenu.show(console, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * Returns a string with the text currently in the console
     *
     * @return String Console Text
     */
    public String getLog() {
        return console.getText();
    }

    public void showKillMinecraft() {
        bottomBar.showKillMinecraft();
    }

    public void hideKillMinecraft() {
        bottomBar.hideKillMinecraft();
    }

    public void setupLanguage() {
        LogManager.debug("Setting up language for console");
        copy.setText(GetText.tr("Copy"));
        bottomBar.setupLanguage();
        LogManager.debug("Finished setting up language for console");
    }

    public void clearConsole() {
        console.setText(null);
    }

    @Override
    public void onRelocalization() {
        copy.setText(GetText.tr("Copy"));
        bottomBar.setupLanguage();
    }
}
