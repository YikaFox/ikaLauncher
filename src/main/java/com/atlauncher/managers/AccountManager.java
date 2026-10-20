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
package com.atlauncher.managers;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.FileSystem;
import com.atlauncher.Gsons;
import com.atlauncher.data.MicrosoftAccount;
import com.atlauncher.utils.SecureStorage;
import com.google.gson.reflect.TypeToken;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;

public class AccountManager {
    private static final Type microsoftAccountListType = new TypeToken<List<MicrosoftAccount>>() {
    }.getType();

    private static final BehaviorSubject<List<MicrosoftAccount>> ACCOUNTS = BehaviorSubject
        .createDefault(Collections.emptyList());

    /**
     * Account using the Launcher
     */
    private static final BehaviorSubject<Optional<MicrosoftAccount>> SELECTED_ACCOUNT = BehaviorSubject
        .createDefault(Optional.empty());

    public static Observable<List<MicrosoftAccount>> getAccountsObservable() {
        return ACCOUNTS;
    }

    public static Observable<Optional<MicrosoftAccount>> getSelectedAccountObservable() {
        return SELECTED_ACCOUNT;
    }

    @Nonnull
    public static List<MicrosoftAccount> getAccounts() {
        return Optional.ofNullable(ACCOUNTS.getValue()).orElse(Collections.emptyList());
    }

    @Nullable
    public static MicrosoftAccount getSelectedAccount() {
        MicrosoftAccount selectedAccount = SELECTED_ACCOUNT.getValue().orElse(null);

        if (isAcceptedMicrosoftAccount(selectedAccount) && getAccounts().contains(selectedAccount)) {
            return selectedAccount;
        }

        return null;
    }

    /**
     * Loads the saved Accounts
     */
    public static void loadAccounts() {
        PerformanceManager.start();
        LogManager.debug("Loading accounts");

        ArrayList<MicrosoftAccount> newAccounts = new ArrayList<>();
        boolean migratedLegacyAccounts = false;

        if (Files.exists(FileSystem.ACCOUNTS)) {
            try {
                byte[] decrypted = SecureStorage.decrypt(Files.readAllBytes(FileSystem.ACCOUNTS));

                List<MicrosoftAccount> accounts = Gsons.DEFAULT.fromJson(
                    new String(decrypted, StandardCharsets.UTF_8), microsoftAccountListType);

                if (accounts != null) {
                    newAccounts.addAll(accounts.stream().filter(AccountManager::isAcceptedMicrosoftAccount)
                        .collect(Collectors.toList()));
                }
            } catch (Exception e) {
                LogManager.logStackTrace("Exception loading encrypted accounts", e);
            }
        }

        // Migrate accounts from the old plaintext file inside the portable Data folder.
        if (newAccounts.isEmpty() && Files.exists(FileSystem.LEGACY_ACCOUNTS)) {
            try (Reader fileReader = new InputStreamReader(
                Files.newInputStream(FileSystem.LEGACY_ACCOUNTS), StandardCharsets.UTF_8)) {
                List<MicrosoftAccount> accounts = Gsons.DEFAULT.fromJson(fileReader, microsoftAccountListType);

                if (accounts != null) {
                    newAccounts.addAll(accounts.stream().filter(AccountManager::isAcceptedMicrosoftAccount)
                        .collect(Collectors.toList()));
                    migratedLegacyAccounts = !newAccounts.isEmpty();
                }
            } catch (Exception e) {
                LogManager.logStackTrace("Exception migrating legacy accounts", e);
            }
        }

        ACCOUNTS.onNext(immutableAccounts(newAccounts));

        for (MicrosoftAccount account : newAccounts) {
            if (account.username.equalsIgnoreCase(App.settings.lastAccount)) {
                SELECTED_ACCOUNT.onNext(Optional.of(account));
            }
        }

        if (!SELECTED_ACCOUNT.getValue().isPresent() && !newAccounts.isEmpty()) {
            SELECTED_ACCOUNT.onNext(Optional.of(newAccounts.get(0)));
        }

        if (migratedLegacyAccounts) {
            LogManager.info("Migrating saved accounts to the encrypted account store");
            saveAccounts();
        }

        LogManager.debug("Finished loading accounts");
        PerformanceManager.end();
    }

    public static void saveAccounts() {
        saveAccounts(ACCOUNTS.getValue());
    }

    private static void saveAccounts(List<MicrosoftAccount> accounts) {
        try {
            String json = Gsons.DEFAULT.toJson(accounts, microsoftAccountListType);
            byte[] encrypted = SecureStorage.encrypt(json.getBytes(StandardCharsets.UTF_8));

            if (FileSystem.ACCOUNTS.getParent() != null) {
                Files.createDirectories(FileSystem.ACCOUNTS.getParent());
            }

            Files.write(FileSystem.ACCOUNTS, encrypted);

            // Never keep a plaintext copy inside the portable Data folder.
            Files.deleteIfExists(FileSystem.LEGACY_ACCOUNTS);
        } catch (Exception e) {
            LogManager.logStackTrace("Failed to save encrypted accounts", e);
        }
    }

    public static void addAccount(MicrosoftAccount account) {
        if (!isAcceptedMicrosoftAccount(account)) {
            LogManager.warn("Refusing to add unsupported Microsoft account " + account);
            return;
        }

        LogManager.info("Added Microsoft Account " + account);

        List<MicrosoftAccount> accounts = new ArrayList<>(getAccounts());
        accounts.add(account);
        ACCOUNTS.onNext(immutableAccounts(accounts));

        if (accounts.size() > 1) {
            // not first account? ask if they want to switch to it
            int ret = DialogManager.optionDialog().setTitle(GetText.tr("Account Added"))
                .setContent(GetText.tr("Account added successfully. Switch to it now?")).setType(DialogManager.INFO)
                .addOption(GetText.tr("Yes"), true).addOption(GetText.tr("No")).show();

            if (ret == 0) {
                switchAccount(account);
            }
        } else {
            // first account? switch to it immediately
            switchAccount(account);
        }

        saveAccounts();
    }

    public static void removeAccount(MicrosoftAccount account) {
        List<MicrosoftAccount> accounts = new ArrayList<>(getAccounts());
        if (SELECTED_ACCOUNT.getValue().orElse(null) == account) {
            if (accounts.size() == 1) {
                // if this was the only account, don't set an account
                switchAccount(null);
            } else {
                // if they have more accounts, switch to the first one
                switchAccount(accounts.get(0));
            }
        }
        accounts.remove(account);
        ACCOUNTS.onNext(immutableAccounts(accounts));
        saveAccounts();
    }

    /**
     * Switch account currently used and save it
     *
     * @param account Account to switch to
     */
    public static void switchAccount(@Nullable MicrosoftAccount account) {
        if (account == null) {
            LogManager.info("Logging out of account");
            SELECTED_ACCOUNT.onNext(Optional.empty());
            App.settings.lastAccount = null;
        } else if (!isAcceptedMicrosoftAccount(account) || !getAccounts().contains(account)) {
            LogManager.warn("Refusing to switch to unsupported Microsoft account " + account);
            return;
        } else {
            LogManager.info("Changed account to " + account);
            SELECTED_ACCOUNT.onNext(Optional.of(account));
            App.settings.lastAccount = account.username;
        }
        App.launcher.refreshPacksBrowserPanel();
        App.settings.save();
    }

    /**
     * Finds an Account from the given username
     *
     * @param username Username of the Account to find
     * @return Account if the Account is found from the username
     */
    public static MicrosoftAccount getAccountByName(String username) {
        for (MicrosoftAccount account : getAccounts()) {
            if (account.username.equalsIgnoreCase(username)) {
                return account;
            }
        }
        return null;
    }

    /**
     * Finds if an Account is available
     *
     * @param username The username of the Account
     * @return true if found, false if not
     */
    public static boolean isAccountByName(String username) {
        for (MicrosoftAccount account : getAccounts()) {
            if (account.username.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAcceptedMicrosoftAccount(MicrosoftAccount account) {
        return account != null && account.getClass() == MicrosoftAccount.class && account.accessToken != null
            && account.oauthToken != null && account.oauthToken.accessToken != null
            && account.oauthToken.refreshToken != null;
    }

    private static List<MicrosoftAccount> immutableAccounts(List<MicrosoftAccount> accounts) {
        return Collections.unmodifiableList(new ArrayList<>(accounts));
    }
}
