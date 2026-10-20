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
package com.atlauncher.gui.tabs.tools;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.TitledBorder;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;

public abstract class AbstractToolPanel extends JPanel {
    protected final JPanel MIDDLE_PANEL = new JPanel(new BorderLayout());
    protected final JPanel BOTTOM_PANEL = new JPanel(new FlowLayout());

    protected final JButton LAUNCH_BUTTON = new JButton(GetText.tr("Launch"));

    /** Wrapping description text for the tool panels. */
    protected static JTextArea createInfoArea(String text) {
        JTextArea area = new JTextArea(text.replace("<br/>", "\n").replace("<br>", "\n"));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setFont(App.THEME.getNormalFont());
        area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return area;
    }

    public AbstractToolPanel(String TITLE) {
        setLayout(new BorderLayout());
        add(MIDDLE_PANEL, BorderLayout.CENTER);
        add(BOTTOM_PANEL, BorderLayout.SOUTH);

        LAUNCH_BUTTON.setFont(App.THEME.getNormalFont().deriveFont(16f));

        if (TITLE != null) {
            setBorder(BorderFactory.createTitledBorder(null, TITLE, TitledBorder.LEADING, TitledBorder.DEFAULT_POSITION,
                    App.THEME.getBoldFont()));
        }
    }
}
