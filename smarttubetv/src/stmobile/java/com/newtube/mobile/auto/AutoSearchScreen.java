package com.newtube.mobile.auto;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;

/** NEWTUBE(android-auto): search box (the car's keyboard or voice); results open as a feed. */
public class AutoSearchScreen extends Screen {
    private final AutoSession mSession;

    public AutoSearchScreen(@NonNull CarContext carContext, AutoSession session) {
        super(carContext);
        mSession = session;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
            @Override
            public void onSearchTextChanged(@NonNull String searchText) {
            }

            @Override
            public void onSearchSubmitted(@NonNull String searchText) {
                String query = searchText.trim();
                if (query.isEmpty()) return;
                getScreenManager().push(new AutoFeedScreen(getCarContext(), mSession,
                        AutoFeedScreen.Kind.SEARCH, query));
            }
        })
                .setHeaderAction(Action.BACK)
                .setSearchHint("Pesquisar vídeos")
                .setShowKeyboardByDefault(true)
                .build();
    }
}
