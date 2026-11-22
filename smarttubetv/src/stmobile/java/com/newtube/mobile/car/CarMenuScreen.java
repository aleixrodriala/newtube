package com.newtube.mobile.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.core.graphics.drawable.IconCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The NewTube menu on the car screen: the same main sections as the phone app (Home,
 * Subscriptions, History, Playlists, Trending) plus the passenger-mode switch.
 *
 * <p>Opened from the "Menu" button on the player screen ({@link CarVideoScreen}). Each section
 * opens a {@link CarVideoListScreen}; picking a video there starts it and returns to the player.</p>
 */
public class CarMenuScreen extends Screen {

    public CarMenuScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        ItemList.Builder list = new ItemList.Builder();

        list.addItem(section(R.string.car_menu_home, R.drawable.ic_nav_home,
                CarVideoListScreen.Source.HOME));
        list.addItem(section(R.string.car_menu_subscriptions, R.drawable.ic_nav_subscriptions,
                CarVideoListScreen.Source.SUBSCRIPTIONS));
        list.addItem(section(R.string.car_menu_history, R.drawable.ic_nav_history,
                CarVideoListScreen.Source.HISTORY));
        list.addItem(section(R.string.car_menu_playlists, R.drawable.ic_player_queue,
                CarVideoListScreen.Source.PLAYLISTS));
        list.addItem(section(R.string.car_menu_trending, R.drawable.ic_car_trending,
                CarVideoListScreen.Source.TRENDING));

        list.addItem(new Row.Builder()
                .setTitle(context.getString(R.string.car_passenger_mode))
                .addText(context.getString(R.string.car_passenger_mode_summary))
                .setToggle(new Toggle.Builder(checked -> {
                    CarModePrefs.setPassengerModeEnabled(context, checked);
                    invalidate();
                }).setChecked(CarModePrefs.isPassengerModeEnabled(context)).build())
                .build());

        return new ListTemplate.Builder()
                .setTitle(context.getString(R.string.car_menu))
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private Row section(int titleRes, int iconRes, CarVideoListScreen.Source source) {
        CarContext context = getCarContext();
        String title = context.getString(titleRes);
        return new Row.Builder()
                .setTitle(title)
                .setImage(new CarIcon.Builder(
                        IconCompat.createWithResource(context, iconRes)).build())
                .setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(
                        new CarVideoListScreen(context, title, source)))
                .build();
    }
}
