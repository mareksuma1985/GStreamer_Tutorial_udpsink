package pl.bezzalogowe.udpsink;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.freedesktop.gstreamer.camera.GstAhc;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getName();
    final MainActivity main = this;
    final int REQUEST_CODE = 200;
    public UpdateUI update;
    public EditText addressEditText;
    public TextView feedback;
    ImageButton stream_start, stream_stop;
    Executor executorAutostart;
    private GstAhc gstAhc;
    private SurfaceView surfaceView;
    private String receiverIP;
    private int portVideo, portAudio;
    private byte resolutionIndex = 3;
    private short videoWidth = 640;
    private short videoHeight = 480;
    private short framerate;
    private int bitrateVideo = 512000;
    private int bitrateAudio = 16000;
    private boolean autostart = false;
    private boolean streamAudio = true;
    private boolean flacEncoding = false;
    private boolean is_playing_desired;

    /* checks if IP address is valid */
    public static boolean validate(final String ip) {
        final String PATTERN = "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";
        Pattern pattern = Pattern.compile(PATTERN);
        Matcher matcher = pattern.matcher(ip);
        return matcher.matches();
    }

    /* converts an IP address written as string to four numbers */
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 23) {
            askPermissions();

            /* https://stackoverflow.com/questions/52378583/how-to-properly-pause-app-until-permission-request-is-finished */
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CODE);
                show_permissions_dialog();
            }
        }

        readPreferences();

        setContentView(R.layout.activity_main);
        surfaceView = (SurfaceView) findViewById(R.id.surface_view);

        try {
            gstAhc = GstAhc.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }

        update = new UpdateUI();
        update.updateConversationHandler = new Handler();

        gstAhc.setErrorListener(new GstAhc.ErrorListener() {
            @Override
            public void error(GstAhc gstAhc, String errorMessage) {
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });

        surfaceView.getHolder().addCallback(gstAhc);
        setOrientation(this.getWindowManager().getDefaultDisplay().getRotation());

        addressEditText = (EditText) this.findViewById(R.id.address);
        addressEditText.setText(receiverIP);
        feedback = (TextView) this.findViewById(R.id.textview_message);

        stream_start = (ImageButton) this.findViewById(R.id.button_video_stream_start);
        stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                //FIXME: rotating the device will revert visibility to these values if locking rotation feature is ever removed
                //TODO: check current pipeline status, then show/hide the right button
                if (!isNetworkAvailable()) {
                    show_info(getResources().getString(R.string.network_title), getResources().getString(R.string.network_content));
                } else {
                    receiverIP = addressEditText.getText().toString();
                    main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                    startVideo();

                    if (streamAudio) {
                        startAudio(flacEncoding);
                    }

                    int orientation = main.getWindowManager().getDefaultDisplay().getRotation();
                    if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
                        if (readVVS() == false) {
                            show_VVS();
                        }
                    }
                    //TODO: native code should trigger hiding/showing buttons
                    stream_start.setVisibility(View.INVISIBLE);
                    stream_stop.setVisibility(View.VISIBLE);
                }
            }
        });

        stream_stop = (ImageButton) this.findViewById(R.id.button_video_stream_stop);
        stream_stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                gstAhc.nativeStreamStop();
                if (streamAudio) {
                    gstAhc.nativeStreamStopAudio();
                }
                main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                //TODO: native code should trigger hiding/showing buttons
                stream_start.setVisibility(View.VISIBLE);
                stream_stop.setVisibility(View.INVISIBLE);
            }
        });

        ImageButton button_usage_info = (ImageButton) this.findViewById(R.id.button_video_stream_info);
        button_usage_info.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                show_usage();
            }
        });

        delayedAutostart();
    }

    private void saveReceiverIP() {
        final EditText addressEditText = (EditText) this.findViewById(R.id.address);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("receiver-ip", addressEditText.getText().toString());
        editor.commit();
    }

    /* checks if VVS warning was already displayed */
    private boolean readVVS() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        boolean vvsConfirmed = settings.getBoolean("vvs-confirmed", false);
        return vvsConfirmed;
    }

    /* saves information that the warning was displayed */
    private void saveVVS() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("vvs-confirmed", true);
        editor.commit();
    }

    private void showPreferences() {
        Intent optionsIntent = new Intent(getApplicationContext(), SettingsActivity.class);
        startActivity(optionsIntent);
        finish();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                showPreferences();
                return true;
            case R.id.other_apps:
                openOtherAppsPage();
                return true;
            case R.id.action_exit: {
                finish();
                System.exit(0);
            }
            return true;
            default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setOrientation(this.getWindowManager().getDefaultDisplay().getRotation());
        /*gstAhc.nativeInit();*/
    }

    protected void onPause() {
        saveReceiverIP();
        /*
        try {
            gstAhc.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        */
        super.onPause();
    }

    protected void onDestroy() {
        //Toast.makeText(this, "Destroy", Toast.LENGTH_LONG).show();
        super.onDestroy();
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d("GStreamer", "Saving state, playing:" + is_playing_desired);
        outState.putBoolean("playing", is_playing_desired);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(main.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /* check'em */
    public void openOtherAppsPage() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/developer?id=bezzalogowe.pl"));
        startActivity(browserIntent);
    }

    private void startVideo() {
        if (validate(receiverIP)) {
            byte[] numbers = tokenize(receiverIP);
            String message = (numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128);
            this.update.updateConversationHandler.post(new UpdateTextThread(addressEditText, message));

            Log.d("IP", "resolution: " + videoWidth + "×" + videoHeight + ", " + framerate + " fps, bitrateVideo: " + bitrateVideo +
                    ", IP: " + (numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128) +
                    ":" + portVideo);
            gstAhc.nativeStreamStart(videoWidth, videoHeight, framerate, bitrateVideo, numbers[0], numbers[1], numbers[2], numbers[3], portVideo);
        } else {
            this.update.updateConversationHandler.post(new UpdateTextThread(addressEditText, "0.0.0.0"));
            Log.d("IP", "Invalid IP address!");
        }
    }

    private void startAudio(boolean flac) {
        if (validate(receiverIP)) {
            byte[] numbers = tokenize(receiverIP);
            String message = (numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128);
            this.update.updateConversationHandler.post(new UpdateTextThread(addressEditText, message));
            gstAhc.nativeStreamStartAudio(flac, bitrateAudio, numbers[0], numbers[1], numbers[2], numbers[3], portAudio);
        } else {
            this.update.updateConversationHandler.post(new UpdateTextThread(addressEditText, "0.0.0.0"));
            Log.d("IP", "Invalid IP address!");
        }
    }

    private void autostart() {
        /** Autostarts streaming if the option is enabled */
        if (autostart) {
            startVideo();
            Log.i(TAG, "Autostarted video: " + receiverIP);
            if (streamAudio) {
                startAudio(flacEncoding ? true : false);
                Log.i(TAG, "Autostarted audio: " + receiverIP);
            }
        }
    }

    public void delayedAutostart() {
        executorAutostart = Executors.newSingleThreadScheduledExecutor();
        Thread newThread = new Thread(new DelayedAutostart());
        newThread.start();
    }

    private void setOrientation(int rotation) {
        GstAhc.Rotate rotate = GstAhc.Rotate.NONE;

        switch (rotation) {
            case Surface.ROTATION_0:
                rotate = GstAhc.Rotate.CLOCKWISE;
                break;
            case Surface.ROTATION_90:
                rotate = GstAhc.Rotate.NONE;
                break;
            case Surface.ROTATION_180:
                rotate = GstAhc.Rotate.COUNTERCLOCKWISE;
                break;
            case Surface.ROTATION_270:
                rotate = GstAhc.Rotate.ROTATE_180;
                break;
        }

        gstAhc.setRotateMethod(rotate);
    }

    protected void askPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE);
    }

    /* reads previously saved preferences, assigns default values if there is none*/
    private void readPreferences() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        receiverIP = settings.getString("receiver-ip", "192.168.0.100");
        portVideo = Integer.valueOf(settings.getString("port-video", "5000"));
        portAudio = Integer.valueOf(settings.getString("port-audio", "5001"));
        framerate = Byte.valueOf(settings.getString("h264-framerate", "15"));
        bitrateVideo = Integer.valueOf(settings.getString("h264-bitrate", "512000"));
        bitrateAudio = Integer.valueOf(settings.getString("opensles-bitrate", "16000"));
        streamAudio = settings.getBoolean("stream-audio", true);
        flacEncoding = settings.getBoolean("flac-toggle", false);
        autostart = settings.getBoolean("autostart", false);
        String videoSize = settings.getString("video-size", "640,480");

        String[] dimensions = videoSize.split("[,x×]");
        if (dimensions.length == 2) {
            /* video-size has two numbers separated by proper delimiter */
            int a = Integer.valueOf(dimensions[0]);
            int b = Integer.valueOf(dimensions[1]);
            /* width is the greater one */
            videoWidth = (short) Math.max(a, b);
            videoHeight = (short) Math.min(a, b);
        } else {
            videoWidth = 640;
            videoHeight = 480;
        }
        Log.d("preferences read", "resolution: " + videoWidth + "×" + videoHeight + ", framerate: " + framerate + ", h264 bitrate: " + bitrateVideo + ", opensles bitrate: " + bitrateAudio + ", FLAC: " + flacEncoding);
    }

    private void openPage(String url) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(browserIntent);
    }

    public void show_usage() {
        String messageVideo = "gst-launch-1.0 udpsrc port=" + portVideo + " ! h264parse ! avdec_h264 ! autovideosink";
        String messageRAW = "udpsrc port=" + portAudio + " ! audio/x-raw, format=S16LE, channels=1, rate=16000 ! autoaudiosink sync=false";
        String messageFLAC = "udpsrc port=" + portAudio + " ! flacparse ! flacdec ! autoaudiosink sync=false";

        /* composes and shows a different message depending on selected audio preferences */
        String messageAudio = flacEncoding ? messageFLAC : messageRAW;
        String message = messageVideo + (streamAudio ? " " + messageAudio : "");

        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(getResources().getString(R.string.usage_title))
                .setMessage(message)
                .setNeutralButton(R.string.close, null)
                .setPositiveButton(R.string.more, new Dialog.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        openPage("https://gstreamer.freedesktop.org/documentation/tools/gst-launch.html");
                    }
                }).show();
    }

    public void show_permissions_dialog() {
        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(getResources().getString(R.string.permission_title))
                .setMessage(getResources().getString(R.string.permission_content))
                .setNeutralButton(R.string.close, new Dialog.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        askPermissions();
                        gstAhc.nativePause();
                        gstAhc.nativePlay();
                    }
                }).show();
    }

    public void show_VVS() {
        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(getResources().getString(R.string.vvs_title))
                .setMessage(getResources().getString(R.string.vvs_content))
                .setNeutralButton(R.string.close, null)
                .setPositiveButton(R.string.more, new Dialog.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        openPage("https://www.google.com/search?q=vertical+video+syndrome");
                    }
                }).show();
        saveVVS();
    }

    public void show_info(String title, String message) {
        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(title)
                .setMessage(message)
                .setNeutralButton(R.string.close, null).show();
    }

    class DelayedAutostart implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(500);
                autostart();
            } catch (Exception e) {
                Log.d(TAG, e.toString());
            }
        }
    }
}
