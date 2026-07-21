package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

/**
 * Accounts bottom sheet - the touch replacement for the TV {@link AccountSettingsPresenter}
 * AppDialog on the You tab's account row (user feedback: "very complex" - a radio group with a
 * cryptic "None" entry plus a remove flow driven by checkboxes acting as buttons).
 *
 * <p>One flat Material sheet instead: every stored account as an avatar row (tap = switch,
 * check = active), a "Use without account" row (the old "None": browse signed-out while keeping
 * the tokens stored), then plain actions - Add account (device-code sign-in), Sign out of the
 * active account behind a real confirm dialog ({@code removeAccount} deletes the stored token),
 * and the advanced TV toggles (password lock / per-account settings / choose on boot) tucked
 * behind "Account settings" via {@link AccountSettingsPresenter#showAdvanced()}.
 *
 * <p>Switch/sign-out propagate through the same backend calls the old dialog used, so the
 * account-change listener chain (Home refresh etc.) is untouched.
 */
final class AccountsSheet {

    private AccountsSheet() {
    }

    /**
     * Entry point. No stored accounts -> straight to the sign-in screen (a sheet with only
     * "Add account" would be noise); otherwise the sheet, even when browsing signed-out, so a
     * stored account is always re-selectable (the old dialog was unreachable in that state).
     */
    static void show(Activity activity, Runnable onAccountsChanged) {
        MediaServiceManager.instance().loadAccounts(accounts -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            if (accounts == null || accounts.isEmpty()) {
                YTSignInPresenter.instance(activity).start();
                return;
            }

            buildAndShow(activity, accounts, onAccountsChanged);
        });
    }

    private static void buildAndShow(Activity activity, List<Account> accounts, Runnable onAccountsChanged) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_accounts, null);
        LinearLayout list = content.findViewById(R.id.accounts_sheet_list);
        LinearLayout actions = content.findViewById(R.id.accounts_sheet_actions);

        Account selected = null;

        for (Account account : accounts) {
            if (account.isSelected()) {
                selected = account;
            }

            addAccountRow(activity, list, account, () -> {
                sheet.dismiss();
                AccountSelectionPresenter.instance(activity).selectAccount(account);
                onAccountsChanged.run();
            });
        }

        addSignedOutRow(activity, list, selected == null, () -> {
            sheet.dismiss();
            AccountSelectionPresenter.instance(activity).selectAccount(null);
            onAccountsChanged.run();
        });

        addActionRow(activity, actions, R.drawable.ic_mobile_add, R.string.dialog_add_account, () -> {
            sheet.dismiss();
            YTSignInPresenter.instance(activity).start();
        });

        if (selected != null) {
            Account selectedAccount = selected;
            addActionRow(activity, actions, R.drawable.ic_mobile_logout, R.string.mobile_accounts_sign_out,
                    () -> confirmSignOut(activity, sheet, selectedAccount, onAccountsChanged));
        }

        addActionRow(activity, actions, R.drawable.ic_mobile_settings, R.string.mobile_accounts_more, () -> {
            sheet.dismiss();
            AccountSettingsPresenter.instance(activity).showAdvanced();
        });

        sheet.setContentView(content);
        sheet.show();
    }

    private static void addAccountRow(Activity activity, LinearLayout parent, Account account, Runnable onClick) {
        View row = inflateAccountRow(activity, parent);

        String name = account.getName() != null ? account.getName() : account.getEmail();
        String email = account.getEmail();
        boolean showEmail = email != null && !email.equals(name);

        TextView nameView = row.findViewById(R.id.mobile_account_row_name);
        TextView emailView = row.findViewById(R.id.mobile_account_row_email);
        ImageView avatarView = row.findViewById(R.id.mobile_account_row_avatar);

        nameView.setText(name);
        emailView.setText(showEmail ? email : null);
        emailView.setVisibility(showEmail ? View.VISIBLE : View.GONE);

        String avatarUrl = account.getAvatarImageUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(activity).load(avatarUrl).circleCrop()
                    .placeholder(R.drawable.ic_mobile_account).into(avatarView);
        } else {
            avatarView.setImageResource(R.drawable.ic_mobile_account);
            tintLikeIcon(activity, avatarView);
        }

        row.findViewById(R.id.mobile_account_row_check)
                .setVisibility(account.isSelected() ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> onClick.run());
    }

    /** The old radio "None", spelled out: keep the stored accounts but browse signed-out. */
    private static void addSignedOutRow(Activity activity, LinearLayout parent, boolean checked, Runnable onClick) {
        View row = inflateAccountRow(activity, parent);

        ImageView avatarView = row.findViewById(R.id.mobile_account_row_avatar);
        avatarView.setImageResource(R.drawable.ic_mobile_account);
        tintLikeIcon(activity, avatarView);

        TextView nameView = row.findViewById(R.id.mobile_account_row_name);
        nameView.setText(R.string.mobile_accounts_signed_out);
        row.findViewById(R.id.mobile_account_row_email).setVisibility(View.GONE);

        row.findViewById(R.id.mobile_account_row_check)
                .setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        row.setOnClickListener(v -> onClick.run());
    }

    private static void addActionRow(Activity activity, LinearLayout parent, int iconRes, int labelRes, Runnable onClick) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_mobile_you_row, parent, false);
        ((ImageView) row.findViewById(R.id.mobile_you_row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.mobile_you_row_label)).setText(labelRes);
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
    }

    private static View inflateAccountRow(Activity activity, LinearLayout parent) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_mobile_account_row, parent, false);
        parent.addView(row);
        return row;
    }

    /** Placeholder person glyph rows: tint like every other sheet icon (real avatars stay untinted). */
    private static void tintLikeIcon(Activity activity, ImageView view) {
        view.setColorFilter(ContextCompat.getColor(activity, R.color.mobile_color_on_surface));
    }

    private static void confirmSignOut(Activity activity, BottomSheetDialog sheet, Account account, Runnable onAccountsChanged) {
        String who = account.getEmail() != null ? account.getEmail() :
                account.getName() != null ? account.getName() : "";

        new MaterialAlertDialogBuilder(activity, R.style.MobileAlertDialog)
                .setTitle(R.string.mobile_accounts_sign_out_title)
                .setMessage(activity.getString(R.string.mobile_accounts_sign_out_confirm, who))
                .setPositiveButton(R.string.mobile_accounts_sign_out, (dialog, which) -> {
                    sheet.dismiss();
                    // Same backend path as the old dialog: delete the stored token, refresh Browse.
                    YouTubeServiceManager.instance().getSignInService().removeAccount(account);
                    BrowsePresenter.instance(activity).refresh(false);
                    onAccountsChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
