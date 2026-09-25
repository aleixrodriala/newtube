package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SearchData;

import java.util.ArrayList;
import java.util.List;

public class SearchSettingsPresenter extends BasePresenter<Void> {
    private final SearchData mSearchData;
    private final GeneralData mGeneralData;

    public SearchSettingsPresenter(Context context) {
        super(context);
        mSearchData = SearchData.instance(context);
        mGeneralData = GeneralData.instance(context);
    }

    public static SearchSettingsPresenter instance(Context context) {
        return new SearchSettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        // NEWTUBE(settings): the speech-engine picker is gone - the phone always asks the system
        // recognizer (MobileSearchActivity) and nothing reads getSpeechRecognizerType().
        appendMiscCategory(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.dialog_search), () -> {
            if (mSearchData.isSearchHistoryDisabled()) {
                MediaServiceManager.instance().clearSearchHistory();
            }
        });
    }

    private void appendSpeechRecognizerCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.speech_recognizer_system, SearchData.SPEECH_RECOGNIZER_SYSTEM},
                {R.string.speech_recognizer_external_1, SearchData.SPEECH_RECOGNIZER_INTENT},
                {R.string.speech_recognizer_external_2, SearchData.SPEECH_RECOGNIZER_GOTEV}}) {
            options.add(UiOptionItem.from(getContext().getString(pair[0]),
                    optionItem -> mSearchData.setSpeechRecognizerType(pair[1]),
                    mSearchData.getSpeechRecognizerType() == pair[1]));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.speech_engine), options);
    }

    private void appendMiscCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        //options.add(UiOptionItem.from(getContext().getString(R.string.disable_popular_searches),
        //        option -> mSearchData.disablePopularSearches(option.isSelected()),
        //        mSearchData.isPopularSearchesDisabled()));

        // NEWTUBE(settings): phone Search keeps only what the phone reads. Dropped (stored values
        // untouched): typing corrections, focus-on-results, keyboard auto-show and the G20s OK-key
        // keyboard fix have no reader outside SearchData; the search exit shortcut is read only by
        // this screen; background play while searching stays under Player, where it also lives.
        options.add(UiOptionItem.from(getContext().getString(R.string.disable_search_history),
                option -> mSearchData.setSearchHistoryDisabled(option.isSelected()),
                mSearchData.isSearchHistoryDisabled()));

        options.add(UiOptionItem.from(getContext().getString(R.string.instant_voice_search),
                option -> mSearchData.setInstantVoiceSearchEnabled(option.isSelected()),
                mSearchData.isInstantVoiceSearchEnabled()));

        settingsPresenter.appendCheckedCategory(getContext().getString(R.string.player_other), options);
    }
}
