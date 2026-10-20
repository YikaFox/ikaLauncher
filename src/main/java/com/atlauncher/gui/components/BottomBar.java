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
package com.atlauncher.gui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Date;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.evnt.listener.ThemeListener;
import com.atlauncher.evnt.manager.ThemeManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.analytics.AnalyticsEvent;
import com.atlauncher.utils.OS;

public abstract class BottomBar extends JPanel implements ThemeListener {
    private static final long serialVersionUID = -7488195680365431776L;

    public final JButton nodeCraftIcon = new SMButton("/assets/image/social/nodecraft.png",
            "Nodecraft - Setup a Minecraft server with an ATLauncher modpack in less than 60 seconds");
    public final JButton discordIcon = new SMButton("/assets/image/social/discord.png", "Discord");
    public final JButton facebookIcon = new SMButton("/assets/image/social/facebook.png", "Facebook");
    public final JButton githubIcon = new SMButton("/assets/image/social/github.png", "GitHub - YikaFox");
    public final JButton twitterIcon = new SMButton("/assets/image/social/twitter.png", "Twitter");
    public final JButton redditIcon = new SMButton("/assets/image/social/reddit.png", "Reddit");

    protected final JPanel rightSide = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 8));

    public BottomBar() {
        super(new BorderLayout());
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("BottomBar.dividerColor")));
        this.setPreferredSize(new Dimension(0, 50));

        this.add(this.rightSide, BorderLayout.EAST);
        this.setupSocialButtonListeners();
        // ikaLauncher only links to the maintainer's GitHub.
        this.rightSide.add(this.githubIcon);

        ThemeManager.addListener(this);
    }

    private void setupSocialButtonListeners() {
        githubIcon.addActionListener(e -> {
            LogManager.info("Opening Up ikaLauncher GitHub Page");
            OS.openWebBrowser("https://github.com/YikaFox");
        });
    }

    @Override
    public void onThemeChange() {
        this.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("BottomBar.dividerColor")));
    }
}
