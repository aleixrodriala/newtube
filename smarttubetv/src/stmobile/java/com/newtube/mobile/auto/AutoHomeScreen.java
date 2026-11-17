package com.newtube.mobile.auto;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/** NEWTUBE(android-auto): first screen - the feeds, search and what is playing. */
public class AutoHomeScreen extends Screen implements AutoPlayer.Listener {
    private final AutoSession mSession;

    public AutoHomeScreen(@NonNull CarContext carContext, AutoSession session) {
        super(carContext);
        mSession = session;
        AutoPlayer player = AutoPlayer.get(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) { player.addListener(AutoHomeScreen.this); }
            @Override public void onStop(@NonNull LifecycleOwner owner) { player.removeListener(AutoHomeScreen.this); }
        });
    }

    private boolean mWasActive;

    @Override
    public void onAutoPlayerChanged() {
        boolean active = AutoPlayer.get(getCarContext()).isActive();
        if (active != mWasActive) invalidate();
    }

    /** NewTube's brand red (colors_mobile.xml mobile_color_primary), same in day and night. */
    static final CarColor RED = CarColor.createCustom(0xFFFF0000, 0xFFFF0000);

    static CarIcon icon(CarContext context, int res) {
        return new CarIcon.Builder(IconCompat.createWithResource(context, res)).setTint(RED).build();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        AutoPlayer player = AutoPlayer.get(context);
        mWasActive = player.isActive();
        ItemList.Builder list = new ItemList.Builder();

        Video current = player.getVideo();
        if (mWasActive && current != null) {
            list.addItem(new Row.Builder()
                    .setTitle("A tocar")
                    .addText(current.title != null ? current.title : "")
                    .setImage(icon(context, R.drawable.ic_auto_play))
                    .setOnClickListener(() -> getScreenManager().push(new AutoPlayerScreen(context, mSession)))
                    .build());
        }
        list.addItem(feedRow(context, "Subscrições", R.drawable.ic_auto_subs, AutoFeedScreen.Kind.SUBSCRIPTIONS));
        list.addItem(feedRow(context, "Início", R.drawable.ic_auto_home, AutoFeedScreen.Kind.HOME));
        list.addItem(feedRow(context, "Continuar a ver", R.drawable.ic_auto_play, AutoFeedScreen.Kind.CONTINUE));
        list.addItem(feedRow(context, "Playlists", R.drawable.ic_auto_playlist, AutoFeedScreen.Kind.PLAYLISTS));
        list.addItem(feedRow(context, "Histórico", R.drawable.ic_auto_history, AutoFeedScreen.Kind.HISTORY));
        list.addItem(new Row.Builder()
                .setTitle("Pesquisar")
                .setImage(icon(context, R.drawable.ic_auto_search))
                .setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(new AutoSearchScreen(context, mSession)))
                .build());

        return new ListTemplate.Builder()
                .setTitle("NewTube")
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build())
                .build();
    }

    private Row feedRow(CarContext context, String title, int iconRes, AutoFeedScreen.Kind kind) {
        return new Row.Builder()
                .setTitle(title)
                .setImage(icon(context, iconRes))
                .setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(new AutoFeedScreen(context, mSession, kind, null)))
                .build();
    }
}
