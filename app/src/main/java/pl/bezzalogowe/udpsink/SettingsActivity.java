package pl.bezzalogowe.udpsink;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.SwitchPreference;
import android.support.v7.app.ActionBar;
import android.preference.PreferenceManager;
import android.preference.RingtonePreference;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.support.v4.app.NavUtils;
import android.content.Intent;
import android.annotation.TargetApi;
import android.os.Build;
import android.preference.PreferenceFragment;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import java.util.List;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
public class SettingsActivity extends AppCompatPreferenceActivity {

    /**
     * A preference value change listener that updates the preference's summary
     * to reflect its new value.
     */
    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            String stringValue = value.toString();

            if (preference instanceof ListPreference) {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);

                // Set the summary to reflect the new value.
                preference.setSummary(
                        index >= 0
                                ? listPreference.getEntries()[index]
                                : null);

            }
            /* ringtone preference unused */
            else if (preference instanceof SwitchPreference) {
                SwitchPreference toggle = (SwitchPreference) preference;
                // Set the summary to reflect the new value.
                toggle.setSummaryOn("enabled");
                toggle.setSummaryOff("disabled");
            } else {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.setSummary(stringValue);
            }
            return true;
        }
    };

    /**
     * Helper method to determine if the device has an extra-large screen. For
     * example, 10" tablets are extra-large.
     */
    private static boolean isXLargeTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     *
     * @see #sBindPreferenceSummaryToValueListener
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    private static void bindSwitchPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

/*
        String stateString = PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getString(preference.getKey(), "false");
        if (stateString.equals("true"))
        {state = true;}
        else
        {state = false;}
*/

        boolean state = PreferenceManager.getDefaultSharedPreferences(preference.getContext()).getBoolean(preference.getKey(), false);

        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference, state);
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupActionBar();
        addPreferencesFromResource(R.xml.pref_general);

        bindPreferenceSummaryToValue(findPreference("h264-resolution"));
        bindPreferenceSummaryToValue(findPreference("h264-framerate"));
        bindPreferenceSummaryToValue(findPreference("h264-bitrate"));
        bindSwitchPreferenceSummaryToValue(findPreference("autostart"));
        bindSwitchPreferenceSummaryToValue(findPreference("stream-audio"));
        bindSwitchPreferenceSummaryToValue(findPreference("flac-toggle"));
        bindPreferenceSummaryToValue(findPreference("opensles-bitrate"));
        bindPreferenceSummaryToValue(findPreference("port-video"));
        bindPreferenceSummaryToValue(findPreference("port-audio"));
    }

    /* when "back" actionbar arrow is clicked */
    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (!super.onMenuItemSelected(featureId, item)) {

                Intent intent = new Intent(getApplicationContext(), MainActivity.class);

                startActivity(intent);
                finish();
            }
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }

    private void saveSize() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();

        Byte resolutionIndex = Byte.valueOf(settings.getString("h264-resolution", "3"));

        short width, height;
        switch (resolutionIndex) {
            case 0:
                width = 176;
                height = 144;
                break;
            case 1:
                width = 320;
                height = 240;
                break;
            case 2:
                width = 352;
                height = 288;
                break;
            case 4:
                width = 720;
                height = 480;
                break;
            case 5:
                width = 800;
                height = 480;
                break;
            case 6:
                width = 960;
                height = 720;
                break;
            case 7:
                width = 1280;
                height = 720;
                break;
            case 8:
                width = 1440;
                height = 1080;
                break;
            case 9:
                width = 1920;
                height = 1080;
                break;
            /* not tested, probably wouldn't work */
            case 10:
                width = 1920;
                height = 1200;
                break;
            case 11:
                width = 2560;
                height = 1440;
                break;
            case 12:
                width = 2560;
                height = 1600;
                break;
            case 13:
                width = 3840;
                height = 2160;
                break;
            default:
                width = 640;
                height = 480;
                break;
        }

        String videoSize = width + "," + height;
        editor.putString("video-size", videoSize);
        editor.commit();
    }
    
    public void onBackPressed() {
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        //TODO: save "video-size", not "h264-resolution"
        saveSize();
        startActivity(intent);
        finish();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onIsMultiPane() {
        return isXLargeTablet(this);
    }

    /**
     * {@inheritDoc}
     */
}
