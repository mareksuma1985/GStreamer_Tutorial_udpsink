package pl.bezzalogowe.udpsink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.freedesktop.gstreamer.GStreamer;
import org.freedesktop.gstreamer.camera.GstAhc;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("gstreamer_udpsink");
        nativeClassInit();
    }

    Camera camera;
    String receiverIP;
    int portVideo, portAudio;
    byte resolutionIndex = 3;
    int bitrateVideo = 512000;
    int bitrateAudio = 16000;
    boolean flacEncoding = false;
    // Native code will use this to keep private data
    private long native_custom_data;
    // Whether the user asked to go to PLAYING
    private boolean is_playing_desired;
    private GstAhc gstAhc;

    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks

    public static boolean validate(final String ip) {
        final String PATTERN = "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
        Pattern pattern = Pattern.compile(PATTERN);
        Matcher matcher = pattern.matcher(ip);
        return matcher.matches();
    }

    public static byte[] tokenize(final String address) {
        String[] tokens = address.split("[.,:]");
        byte[] numbers = new byte[]{-128, -128, -128, -128};
        if (0 < tokens.length && tokens.length <= 4) {
            for (int i = 0; i < tokens.length; i++) {
                if (Byte.MIN_VALUE <= (Integer.valueOf(tokens[i]) - 128) && (Integer.valueOf(tokens[i]) - 128) <= Byte.MAX_VALUE) {
                    numbers[i] = (byte) (Integer.valueOf(tokens[i]) - 128);
                } else {
                    break;
                }
            }
        }
        return numbers;
    }

    private native void nativeInit();     // Initialize native code, build pipeline, etc

    private native void nativeFinalize(); // Destroy pipeline and shutdown native code

    /** video */
    private native void nativeStreamStart(char resolution, int bitrate, char ch0, char ch1, char ch2, char ch3, int port);

    private native void nativeStreamStop();

    /** audio */
    private native void nativeStreamStartAudio(boolean flac, int bitrate, char ch0, char ch1, char ch2, char ch3, int port);

    private native void nativeStreamStopAudio();

    /** gst-launch audio */
    private native void nativePlay(); // Set pipeline to PLAYING

    private native void nativePause(); // Set pipeline to PAUSED

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        readPreferences();

        if (Build.VERSION.SDK_INT >= 23) {
            askPermissions();
        }

        try {
            GStreamer.init(this);
        } catch (Exception e) {
            e.printStackTrace();
            finish();
            return;
        }

        try {
            gstAhc = GstAhc.init(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        final EditText addressEditText = (EditText) this.findViewById(R.id.address);
        addressEditText.setText(receiverIP);

        ImageButton stream_start = (ImageButton) this.findViewById(R.id.button_video_stream_start);
        stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String address = addressEditText.getText().toString();
                if (validate(address)) {
                    byte[] numbers = tokenize(address);
                    addressEditText.setText((numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128));
                    nativeStreamStart((char) resolutionIndex, bitrateVideo, (char) numbers[0], (char) numbers[1], (char) numbers[2], (char) numbers[3], portVideo);
                } else {
                    addressEditText.setText("0.0.0.0");
                    Log.d("IP", "Invalid IP address!");
                }
            }
        });

        ImageButton stream_stop = (ImageButton) this.findViewById(R.id.button_video_stream_stop);
        stream_stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                nativeStreamStop();
            }
        });

        ImageButton audio_stream_start = (ImageButton) this.findViewById(R.id.button_audio_stream_start);
        audio_stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String address = addressEditText.getText().toString();
                if (validate(address)) {
                    byte[] numbers = tokenize(address);
                    addressEditText.setText((numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128));
                    nativeStreamStartAudio(false, bitrateAudio, (char) numbers[0], (char) numbers[1], (char) numbers[2], (char) numbers[3], portAudio);
                } else {
                    addressEditText.setText("0.0.0.0");
                    Log.d("IP", "Invalid IP address!");
                }
            }
        });

        ImageButton audio_stream_stop = (ImageButton) this.findViewById(R.id.button_audio_stream_stop);
        audio_stream_stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                nativeStreamStopAudio();
            }
        });

        /** flac audio */

        ImageButton audio_flac_stream_start = (ImageButton) this.findViewById(R.id.button_flac_stream_start);
        audio_flac_stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {

/*
                is_playing_desired = true;
                nativePlay();
*/
                String address = addressEditText.getText().toString();
                if (validate(address)) {
                    byte[] numbers = tokenize(address);
                    addressEditText.setText((numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128));
                    nativeStreamStartAudio(true, bitrateAudio, (char) numbers[0], (char) numbers[1], (char) numbers[2], (char) numbers[3], portAudio);
                } else {
                    addressEditText.setText("0.0.0.0");
                    Log.d("IP", "Invalid IP address!");
                }
            }
        });

        ImageButton audio_flac_stream_stop = (ImageButton) this.findViewById(R.id.button_flac_stream_stop);
        audio_flac_stream_stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
/*
                is_playing_desired = false;
                nativePause();
*/
                nativeStreamStopAudio();
            }
        });

/*
        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            Log.i("GStreamer", "Activity created. Saved state is playing:" + is_playing_desired);
        } else {
            is_playing_desired = false;
            Log.i("GStreamer", "Activity created. There is no saved state, playing: false");
        }
*/


/*
        this.findViewById(R.id.button_play).setEnabled(false);
        this.findViewById(R.id.button_stop).setEnabled(false);
*/

        ImageButton button_video_stream_info = (ImageButton) this.findViewById(R.id.button_video_stream_info);
        button_video_stream_info.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                show_info(getResources().getString(R.string.receiving_video_title), "gst-launch-1.0 udpsrc port=" + portVideo + " ! h264parse ! avdec_h264 ! autovideosink");
            }
        });

        ImageButton button_audio_info = (ImageButton) this.findViewById(R.id.button_audio_info);
        button_audio_info.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                show_info(getResources().getString(R.string.receiving_audio_title), "gst-launch-1.0 udpsrc port=" + portAudio + " ! audio/x-raw, format=S16LE, channels=1, rate=16000 ! autoaudiosink");
            }
        });

        ImageButton button_audio_flac_info = (ImageButton) this.findViewById(R.id.button_audio_flac_info);
        button_audio_flac_info.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                show_info(getResources().getString(R.string.receiving_audio_flac_title), "gst-launch-1.0 udpsrc port=" + portAudio + " ! flacparse ! flacdec ! autoaudiosink");
            }
        });


        // Make audio buttons and video label visible only if opensles is enabled
        if (flacEncoding == true) {
            LinearLayout layoutAudio = (LinearLayout) this.findViewById(R.id.LayoutFlac);
            layoutAudio.setVisibility(View.VISIBLE);
        } else {
            LinearLayout layoutAudio = (LinearLayout) this.findViewById(R.id.LayoutAudio);
            layoutAudio.setVisibility(View.VISIBLE);
        }
    }

    private void readPreferences() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        receiverIP = settings.getString("receiver-ip", "192.168.0.100");
        portVideo = Integer.valueOf(settings.getString("port-video", "5000"));
        portAudio = Integer.valueOf(settings.getString("port-audio", "5001"));
        resolutionIndex = Byte.valueOf(settings.getString("h264-resolution", "3"));
        bitrateVideo = Integer.valueOf(settings.getString("h264-bitrate", "512000"));
        bitrateAudio = Integer.valueOf(settings.getString("opensles-bitrate", "16000"));
        flacEncoding = settings.getBoolean("flac-toggle", false);
/*
        Toast.makeText(this, getResources().getStringArray(R.array.video_resolutions_sizes)[resolutionIndex] + " (" + getResources().getStringArray(R.array.video_resolutions_standards)[resolutionIndex] + ")\n" + bitrateVideo + " b/s", Toast.LENGTH_LONG).show();
*/
        Log.d("preferences read", "resolution index: " + resolutionIndex + ", h264 bitrate: " + bitrateVideo + ", opensles bitrate: " + bitrateAudio + ", FLAC: " + flacEncoding);
    }

    @Override
    public void onStart(/*Bundle savedInstanceState*/) {
        super.onStart();
        //Toast.makeText(this, "Start", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onResume(/*Bundle savedInstanceState*/) {
        super.onResume();
        //Toast.makeText(this, "Resume", Toast.LENGTH_LONG).show();
        nativeInit();
    }

    private void saveReceiverIP() {
        final EditText addressEditText = (EditText) this.findViewById(R.id.address);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("receiver-ip", addressEditText.getText().toString());
        editor.commit();
    }

    private void showOptions() {
        //Intent optionsIntent = new Intent(this, OptionsActivity.class);
        Intent optionsIntent = new Intent(getApplicationContext(), SettingsActivity.class);
        //optionsIntent.putExtra(EXTRA_MESSAGE, "settings");

        startActivity(optionsIntent);
        finish();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    public void openOtherAppsPage() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/developer?id=bezzalogowe.pl"));
        startActivity(browserIntent);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                showOptions();
                return true;
            case R.id.other_apps:
                openOtherAppsPage();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d("GStreamer", "Saving state, playing:" + is_playing_desired);
        outState.putBoolean("playing", is_playing_desired);
    }

    protected void onPause() {
        saveReceiverIP();
        nativeFinalize();
        //Toast.makeText(this, "Pause", Toast.LENGTH_LONG).show();
        super.onPause();
    }

    protected void onDestroy() {
        //Toast.makeText(this, "Destroy", Toast.LENGTH_LONG).show();
        super.onDestroy();
    }

    protected void askPermissions() {
        String[] permissions = {
                "android.permission.INTERNET",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO"
        };
        int requestCode = 200;

        if (Build.VERSION.SDK_INT >= 23) {
            if (this.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                this.requestPermissions(permissions, requestCode);
            }
        }
    }

    private void setResolution() {
        camera = Camera.open();
        Camera.Parameters parameters;
        parameters = camera.getParameters();

        List<Size> vidsizes = parameters.getSupportedVideoSizes();

        for (Size size : vidsizes) {
            Log.d("sizes", "VideoSize: " + size.width + "x" + size.height);
        }

/**
 preview sizes: 1920x1080,1440x1080,1280x720,1056x864,960x720,800x480,720x480,640x480,352x288,320x240,176x144
 video sizes: 1920x1080,1440x1080,1280x720,800x450,800x480,720x480,640x480,352x288,320x240,176x144
 picture sizes: 5312x2988,3984x2988,3264x2448,3264x1836,2560x1920,2048x1152,1920x1080,1280x960,1280x720,800x480,640x480,320x240
 */
        parameters.setPreviewSize(176, 144);
        try {
            camera.stopPreview();

            camera.setParameters(parameters);

            camera.startPreview();
        } catch (Exception e) {
            Log.d("sizes", "error: " + e.toString());
            e.printStackTrace();
        }
        camera.release();
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        runOnUiThread(new Runnable() {
            public void run() {
                tv.setText(message);
            }
        });
    }

    public void show_info(String title, String message) {
        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(title)
                .setMessage(message)
                .setNeutralButton(R.string.close, null).show();
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized() {
        // Re-enable buttons, now that GStreamer is initialized
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            public void run() {
                activity.findViewById(R.id.button_video_stream_start).setEnabled(true);
                activity.findViewById(R.id.button_video_stream_stop).setEnabled(true);

                activity.findViewById(R.id.button_audio_stream_start).setEnabled(true);
                activity.findViewById(R.id.button_audio_stream_stop).setEnabled(true);

                activity.findViewById(R.id.button_flac_stream_start).setEnabled(true);
                activity.findViewById(R.id.button_flac_stream_stop).setEnabled(true);
            }
        });
    }
}