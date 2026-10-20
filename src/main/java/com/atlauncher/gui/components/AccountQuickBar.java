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
package com.atlauncher.gui.components;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.data.MicrosoftAccount;
import com.atlauncher.evnt.listener.RelocalizationListener;
import com.atlauncher.evnt.manager.RelocalizationManager;
import com.atlauncher.gui.AccountsDropDownRenderer;
import com.atlauncher.gui.dialogs.ChangeSkinDialog;
import com.atlauncher.gui.dialogs.LoginWithMicrosoftDialog;
import com.atlauncher.gui.dialogs.ProgressDialog;
import com.atlauncher.managers.AccountManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.utils.Utils;
import com.gitlab.doomsdayrs.lib.rxswing.schedulers.SwingSchedulers;

/** Account switcher on the Launch tab. */
public final class AccountQuickBar extends JPanel implements RelocalizationListener {
    private static final long serialVersionUID = 1L;

    private final JComboBox<MicrosoftAccount> accountSelector = new JComboBox<>();
    private final JButton loginButton = new JButton();
    private final JButton addAccountButton = new JButton("+");
    private final JLabel loginHint = new JLabel(GetText.tr(
            "We need to confirm that you actually own Minecraft, so you need to sign in with a Microsoft account."));
    private final JPopupMenu accountMenu = new JPopupMenu();

    private JMenuItem deleteItem;
    private JMenuItem refreshTokenItem;
    private JMenuItem changeSkinItem;
    private JMenuItem updateSkinItem;
    private JMenuItem updateUsernameItem;

    private boolean dontSave = false;

    public AccountQuickBar() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);

        accountSelector.setRenderer(new AccountsDropDownRenderer());
        accountSelector.setMaximumSize(new Dimension(200, 28));
        accountSelector.setPreferredSize(new Dimension(170, 28));
        accountSelector.setToolTipText(GetText.tr("Right click to manage this account"));
        accountSelector.addItemListener(event -> {
            if (dontSave || event.getStateChange() != ItemEvent.SELECTED) {
                return;
            }

            if (event.getItem() instanceof MicrosoftAccount) {
                AccountManager.switchAccount((MicrosoftAccount) event.getItem());
            }
        });

        MouseAdapter menuListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e);
            }
        };
        accountSelector.addMouseListener(menuListener);

        loginButton.setText(GetText.tr("Sign In with Microsoft"));

        // logo is 4x, scale it down
        Image logo = Utils.getImage("/assets/image/providers/microsoft-logo.png");
        if (logo != null) {
            loginButton.setIcon(new javax.swing.ImageIcon(
                    logo.getScaledInstance(18, 18, Image.SCALE_SMOOTH)));
        }
        loginButton.setToolTipText(GetText.tr("Sign In with Microsoft"));
        loginButton.setFocusPainted(false);
        loginButton.setIconTextGap(8);
        // visible button style
        loginButton.putClientProperty("JButton.buttonType", "roundRect");
        loginButton.addActionListener(e -> login());

        addAccountButton.setToolTipText(GetText.tr("Add An Account"));
        addAccountButton.addActionListener(e -> login());

        buildAccountMenu();

        add(accountSelector);
        add(Box.createHorizontalStrut(4));
        add(addAccountButton);
        add(Box.createHorizontalStrut(4));
        add(loginButton);
        add(Box.createHorizontalStrut(12));
        add(loginHint);

        AccountManager.getAccountsObservable().observeOn(SwingSchedulers.edt()).subscribe(accounts -> refresh());
        AccountManager.getSelectedAccountObservable().observeOn(SwingSchedulers.edt())
                .subscribe(account -> refreshSelection());

        refresh();
        RelocalizationManager.addListener(this);
    }

    private void maybeShowMenu(MouseEvent e) {
        if (e.isPopupTrigger() && getSelectedAccount() != null) {
            accountMenu.show(accountSelector, e.getX(), e.getY());
        }
    }

    private void buildAccountMenu() {
        changeSkinItem = new JMenuItem(GetText.tr("Change Skin"));
        changeSkinItem.addActionListener(e -> {
            MicrosoftAccount account = getSelectedAccount();
            if (account != null) {
                ChangeSkinDialog dialog = new ChangeSkinDialog(account);
                dialog.setVisible(true);
            }
        });
        accountMenu.add(changeSkinItem);

        updateSkinItem = new JMenuItem(GetText.tr("Reload Skin"));
        updateSkinItem.addActionListener(e -> {
            MicrosoftAccount account = getSelectedAccount();
            if (account != null) {
                account.updateSkin();
            }
        });
        accountMenu.add(updateSkinItem);

        updateUsernameItem = new JMenuItem(GetText.tr("Update Username"));
        updateUsernameItem.addActionListener(e -> {
            MicrosoftAccount account = getSelectedAccount();
            if (account != null) {
                account.updateUsername();
                AccountManager.saveAccounts();
                refresh();
            }
        });
        accountMenu.add(updateUsernameItem);

        refreshTokenItem = new JMenuItem(GetText.tr("Refresh Access Token"));
        refreshTokenItem.addActionListener(e -> refreshAccessToken());
        accountMenu.add(refreshTokenItem);

        accountMenu.addSeparator();

        deleteItem = new JMenuItem(GetText.tr("Delete"));
        deleteItem.addActionListener(e -> {
            MicrosoftAccount account = getSelectedAccount();
            if (account == null) {
                return;
            }

            int ret = DialogManager.yesNoDialog()
                    .setTitle(GetText.tr("Delete"))
                    .setContent(GetText.tr("Are you sure you want to delete this account?"))
                    .setType(DialogManager.WARNING).show();

            if (ret == DialogManager.YES_OPTION) {
                AccountManager.removeAccount(account);
                refresh();
            }
        });
        accountMenu.add(deleteItem);
    }

    private MicrosoftAccount getSelectedAccount() {
        Object selected = accountSelector.getSelectedItem();
        return selected instanceof MicrosoftAccount ? (MicrosoftAccount) selected : null;
    }

    private void login() {
        int accountsBefore = AccountManager.getAccounts().size();

        LoginWithMicrosoftDialog dialog = new LoginWithMicrosoftDialog();
        dialog.setVisible(true);

        if (AccountManager.getAccounts().size() != accountsBefore) {
            if (dialog.account != null) {
                dialog.account.updateSkin();
            }

            SwingUtilities.invokeLater(() -> {
                refresh();
                refreshSelection();
            });
        }
    }

    private void refresh() {
        dontSave = true;
        accountSelector.removeAllItems();

        for (MicrosoftAccount account : AccountManager.getAccounts()) {
            accountSelector.addItem(account);
        }

        refreshSelection();

        boolean hasAccounts = accountSelector.getItemCount() > 0;
        accountSelector.setVisible(hasAccounts);
        addAccountButton.setVisible(hasAccounts);
        loginButton.setVisible(!hasAccounts);
        loginHint.setVisible(!hasAccounts);

        dontSave = false;
    }

    private void refreshSelection() {
        MicrosoftAccount selected = AccountManager.getSelectedAccount();

        if (selected != null) {
            dontSave = true;
            accountSelector.setSelectedItem(selected);
            dontSave = false;
        }
    }

    private void refreshAccessToken() {
        MicrosoftAccount account = getSelectedAccount();
        if (account == null) {
            return;
        }

        final ProgressDialog<Boolean> dialog = new ProgressDialog<>(
                GetText.tr("Refreshing Access Token For {0}", account.minecraftUsername), 0,
                GetText.tr("Refreshing Access Token For {0}", account.minecraftUsername),
                "Aborting refreshing access token for " + account.minecraftUsername);

        dialog.addThread(new Thread(() -> {
            boolean success = account.refreshAccessToken(true);
            if (!success) {
                account.mustLogin = true;
            }
            dialog.setReturnValue(success);
            dialog.close();
        }));
        dialog.start();

        if (Boolean.TRUE.equals(dialog.getReturnValue())) {
            AccountManager.saveAccounts();

            DialogManager.okDialog()
                    .setTitle(GetText.tr("Access Token Refreshed"))
                    .setContent(GetText.tr("Access token refreshed successfully"))
                    .setType(DialogManager.INFO)
                    .show();
        } else {
            DialogManager.okDialog()
                    .setTitle(GetText.tr("Failed To Refresh Access Token"))
                    .setContent(GetText.tr("Failed to refresh accessToken. Please login again."))
                    .setType(DialogManager.ERROR)
                    .show();

            MicrosoftAccount current = getSelectedAccount();
            if (current != null) {
                LoginWithMicrosoftDialog dialog2 = new LoginWithMicrosoftDialog(current);
                dialog2.setVisible(true);
                refresh();
            }
        }
    }

    @Override
    public void onRelocalization() {
        loginButton.setText(GetText.tr("Sign In with Microsoft"));
        loginButton.setToolTipText(GetText.tr("Sign In with Microsoft"));
        loginHint.setText(GetText.tr(
                "We need to confirm that you actually own Minecraft, so you need to sign in with a Microsoft account."));
        addAccountButton.setToolTipText(GetText.tr("Add An Account"));
        accountSelector.setToolTipText(GetText.tr("Right click to manage this account"));

        if (changeSkinItem != null) {
            changeSkinItem.setText(GetText.tr("Change Skin"));
        }
        if (updateSkinItem != null) {
            updateSkinItem.setText(GetText.tr("Reload Skin"));
        }
        if (updateUsernameItem != null) {
            updateUsernameItem.setText(GetText.tr("Update Username"));
        }
        if (refreshTokenItem != null) {
            refreshTokenItem.setText(GetText.tr("Refresh Access Token"));
        }
        if (deleteItem != null) {
            deleteItem.setText(GetText.tr("Delete"));
        }
    }
}
