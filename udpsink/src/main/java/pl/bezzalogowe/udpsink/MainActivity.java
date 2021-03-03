package pl.bezzalogowe.udpsink;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
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

import com.huawei.agconnect.config.AGConnectServicesConfig;
import com.huawei.hms.aaid.HmsInstanceId;

import org.freedesktop.gstreamer.camera.GstAhc;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.text.InputType.TYPE_CLASS_NUMBER;
import static android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
import static android.text.InputType.TYPE_NUMBER_VARIATION_NORMAL;

public class MainActivity extends Activity {
    private final static String TAG = MainActivity.class.getName();
    final MainActivity main = this;
    final int REQUEST_CODE = 200;
    public UpdateUI update;
    //public EditText addressEditText;
    public TextView feedback;
    ImageButton menuButton, stream_start, stream_stop;
    Executor executorAutostart;
    /* https://stackoverflow.com/questions/2250112/why-doesnt-logcat-show-anything-in-my-android/10963065#10963065
     Code for enabling logs on Huawei phones: *#*#2846579#*#*
     */
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
    private boolean autorotation = true;
    private boolean packetization = false;
    private boolean streamAudio = true;
    private boolean flacEncoding = false;
    private String pushtoken;
    // Whether the user asked to go to PLAYING
    private boolean is_playing_desired;

    SharedPreferences settings;

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
        setContentView(R.layout.main_horizontal);
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

        feedback = (TextView) this.findViewById(R.id.caption);

        menuButton = (ImageButton) findViewById(R.id.button_menu);

        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOptionsMenu();
            }
        });

        stream_start = (ImageButton) this.findViewById(R.id.button_video_stream_start);
        stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                //FIXME: rotating the device will revert visibility to these values if locking rotation feature is ever removed
                //TODO: check pipeline status, then show/hide the right button
                if (!isNetworkAvailable()) {
                    show_info(getResources().getString(R.string.network_title), getResources().getString(R.string.network_content));
                } else {
                    main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                    startVideo();

                    if (streamAudio) {
                        startAudio(flacEncoding);
                    }

                    int orientation = main.getWindowManager().getDefaultDisplay().getRotation();
                    if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
                        if (readVVS() == false) {
                            show_VVS();
                        } else {
                            //TODO: Push notification or something less annoying than an alert
                        }
                    }
                    //TODO: native code should trigger hiding and showing buttons
                    stream_start.setVisibility(View.GONE);
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
                main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                //TODO: native code should trigger hiding and showing buttons
                stream_start.setVisibility(View.VISIBLE);
                stream_stop.setVisibility(View.GONE);
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

/*
    private void saveReceiverIP() {
        final EditText addressEditText = (EditText) this.findViewById(R.id.address);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("receiver-ip", addressEditText.getText().toString());
        editor.commit();
    }
*/

    private boolean readVVS() {
        //SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        boolean vvsConfirmed = settings.getBoolean("vvs-confirmed", false);
        return vvsConfirmed;
    }

    private void saveVVS() {
        //SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean("vvs-confirmed", true);
        editor.commit();
        //Toast.makeText(this, "The dialog will not be displayed again.", Toast.LENGTH_LONG).show();
    }

    //HUAWEI
    private void saveToken(String value) {
        //SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("pushtoken", value);
        editor.commit();
        Log.d(TAG, "Saved token: " + value);
    }

    private void showPreferences() {
        //TODO: stop streaming
        Intent optionsIntent = new Intent(getApplicationContext(), SettingsActivity.class);
        startActivity(optionsIntent);
        finish();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.set_ip:
                showPopUpSetIP();
                return true;
                /*
            case R.id.set_video_port:
                showPopUpSetPort();
                return true;
                */
            case R.id.preferences:
                showPreferences();
                return true;
            case R.id.help:
                openHelpPage();
                return true;
            case R.id.other_apps:
                openOtherAppsPage();
                return true;
            case R.id.source_code:
                openSourceCodePage();
                return true;
            case R.id.exit: {
                finish();
                System.exit(0);
            }
            return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onResume(/*Bundle savedInstanceState*/) {
        super.onResume();
        //Toast.makeText(this, "Resume", Toast.LENGTH_LONG).show();
        setOrientation(this.getWindowManager().getDefaultDisplay().getRotation());
        /*gstAhc.nativeInit();*/
    }

    protected void onPause() {
/*
        saveReceiverIP();
        Toast.makeText(this, "Pause", Toast.LENGTH_LONG).show();
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

    /* Set IP */
    public void setIP(View view) {

    }

    /*
     * http://www.androiddom.com/2011/06/displaying-android-pop-up-dialog_13.html
     */

    private void showPopUpSetIP() {
        AlertDialog.Builder helpBuilder = new AlertDialog.Builder(this);
        helpBuilder.setTitle(getResources().getString(R.string.set_ip_title));
        helpBuilder.setMessage(getResources().getString(R.string.set_ip_message));

        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        //input.setInputType(InputType.TYPE_CLASS_NUMBER);
        //final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(main);
        receiverIP = settings.getString("receiver-ip", "192.168.0.100");
        portVideo = Integer.valueOf(settings.getString("port-video", "5000"));
        input.setText(receiverIP + ":" + portVideo);
        helpBuilder.setView(input);
        helpBuilder.setNeutralButton(R.string.close, null);
        helpBuilder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                try {
                    receiverIP = input.getText().toString();
                    String[] result = input.getText().toString().split(":");

                    // assigns IP
                    if (validate(result[0])) {
                        receiverIP = result[0];

                        //assigns port
                        if (result.length > 1) {
                            portVideo = Integer.valueOf(result[1]);
                        }

                        byte[] numbers = tokenize(receiverIP);
                        String message = (numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128);
                        input.setText(message + ":" + portVideo);
                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString("receiver-ip", receiverIP);
                        editor.putString("port-video", Integer.toString(portVideo));
                        editor.commit();
                    } else {
                        input.setText("0.0.0.0:" + result[1]);
                    }
                } catch (NumberFormatException e) {
                    Log.d("NumberFormatException", "Error: " + e);
                    e.printStackTrace();
                }
            }
        });

        AlertDialog helpDialog = helpBuilder.create();
        helpDialog.show();
    }

    private void showPopUpSetPort() {
        AlertDialog.Builder helpBuilder = new AlertDialog.Builder(this);
        helpBuilder.setTitle(getResources().getString(R.string.set_port_title));
        helpBuilder.setMessage(getResources().getString(R.string.set_port_message));

        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(TYPE_CLASS_NUMBER);
        //SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        //final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(main);
        input.setText(settings.getString("port-video", "5000"));
        helpBuilder.setView(input);
        helpBuilder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {

                try {
                    portVideo = Integer.valueOf(input.getText().toString());
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putInt("port-video", portVideo);
                    editor.commit();

                } catch (NumberFormatException e) {
                    Log.d("NumberFormatException", "Error: " + e);
                    e.printStackTrace();
                }
            }
        });

        AlertDialog helpDialog = helpBuilder.create();
        helpDialog.show();
    }

    private void openHelpPage() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://gstreamer.freedesktop.org/documentation/udp/udpsrc.html"));
        startActivity(browserIntent);
    }

    public void openOtherAppsPage() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/developer?id=bezzalogowe.pl"));
        startActivity(browserIntent);
    }

    public void openSourceCodePage() {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mareksuma1985/GStreamer_Tutorial_udpsink"));
        startActivity(browserIntent);
    }

    // HUAWEI Push notifications
    private void showLog(final String log) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                View tvView = feedback;
                if (tvView instanceof TextView) {
                    ((TextView) tvView).setText(log);
                    Toast.makeText(MainActivity.this, pushtoken, Toast.LENGTH_SHORT).show();
                }
                Log.d("GStreamer", "Push token is: " + pushtoken);
            }
        });
    }

    private void getToken() {
        // get token
        new Thread() {
            @Override
            public void run() {
                try {
                    String appId = AGConnectServicesConfig.fromContext(MainActivity.this).getString("client/app_id");
                    pushtoken = HmsInstanceId.getInstance(MainActivity.this).getToken(appId, "HCM");
                    if (!TextUtils.isEmpty(pushtoken)) {
                        Log.i(TAG, "get token: " + pushtoken);
                        saveToken(pushtoken);
                        //showLog(pushtoken);
                    }
                } catch (Exception e) {
                    Log.i(TAG, "getToken failed, " + e);
                }
            }
        }.start();
    }

    private void startVideo() {
        byte[] ip_as_bytes = tokenize(receiverIP);
        Log.d("IP", "resolution: " + videoWidth + "×" + videoHeight + ", " + framerate + " fps, bitrateVideo: " + bitrateVideo +
                ", IP: " + (ip_as_bytes[0] + 128) + "." + (ip_as_bytes[1] + 128) + "." + (ip_as_bytes[2] + 128) + "." + (ip_as_bytes[3] + 128) +
                ":" + portVideo);

        gstAhc.nativeStreamStart(videoWidth, videoHeight, framerate, bitrateVideo, autorotation, packetization, ip_as_bytes[0], ip_as_bytes[1], ip_as_bytes[2], ip_as_bytes[3], portVideo);
    }

    private void startAudio(boolean flac) {
        byte[] ip_as_bytes = tokenize(receiverIP);
        String message = (ip_as_bytes[0] + 128) + "." + (ip_as_bytes[1] + 128) + "." + (ip_as_bytes[2] + 128) + "." + (ip_as_bytes[3] + 128);
        //this.update.updateConversationHandler.post(new UpdateTextThread(feedback, "streaming audio started"));
        gstAhc.nativeStreamStartAudio(flac, bitrateAudio, ip_as_bytes[0], ip_as_bytes[1], ip_as_bytes[2], ip_as_bytes[3], portAudio);
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

    //unused
/*
    class DelayedInit implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(2000);
            } catch (Exception e) {
                Log.d("delayed init", e.toString());
            }
        }
    }

    public void delayedInit() {
        executorInit = Executors.newSingleThreadScheduledExecutor();
        Thread newThread = new Thread(new DelayedInit());
        newThread.start();
    }
*/
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
                /* to be able to put GUI upside down, you have to put this in manifest's activity section:
                android:screenOrientation="fullSensor" */
                break;
            case Surface.ROTATION_270:
                rotate = GstAhc.Rotate.ROTATE_180;
                break;
        }
        //Toast.makeText(this, "orientation: " + rotate, Toast.LENGTH_LONG).show();
        gstAhc.setRotateMethod(rotate);
    }

    protected void askPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
                /*"android.permission.CAMERA",
                  "android.permission.RECORD_AUDIO"*/
        };
        // int requestCode = 200;
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE);
    }

    private void readPreferences() {
        settings = PreferenceManager.getDefaultSharedPreferences(this);
        receiverIP = settings.getString("receiver-ip", "192.168.0.100");
        portVideo = Integer.valueOf(settings.getString("port-video", "5000"));
        portAudio = Integer.valueOf(settings.getString("port-audio", "5001"));
        //resolutionIndex = Byte.valueOf(settings.getString("h264-resolution", "3"));
        framerate = Byte.valueOf(settings.getString("h264-framerate", "15"));
        bitrateVideo = Integer.valueOf(settings.getString("h264-bitrate", "512000"));
        bitrateAudio = Integer.valueOf(settings.getString("opensles-bitrate", "16000"));
        streamAudio = settings.getBoolean("stream-audio", true);
        flacEncoding = settings.getBoolean("flac-toggle", false);
        autostart = settings.getBoolean("autostart", false);
        autorotation = settings.getBoolean("video-direction", true);
        packetization = settings.getBoolean("rtph264pay", false);

        String deviceManufacturer = android.os.Build.MANUFACTURER;
        Log.d(TAG, "MANUFACTURER: " + deviceManufacturer);

        if (deviceManufacturer.toUpperCase().equals("HUAWEI")) {
            String readtoken = settings.getString("pushtoken", "");
            if (readtoken.length() > 0) {
                Log.d(TAG, "Already got token: " + readtoken);
            } else {
                getToken();
            }
        }

        String videoSize = settings.getString("video-size", "640,480");
        String[] dimensions = videoSize.split("[,x×]");
        if (dimensions.length == 2) {
            //video-size has two numbers separated by proper delimiter
            int a = Integer.valueOf(dimensions[0]);
            int b = Integer.valueOf(dimensions[1]);
            videoWidth = (short) Math.max(a, b);
            videoHeight = (short) Math.min(a, b);
        } else {
            //default values
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
        String messageVideoRTP = "gst-launch-1.0 udpsrc port=" + portVideo + " caps='application/x-rtp, media=(string)video, clock-rate=(int)90000, encoding-name=(string)H264' ! rtpjitterbuffer ! rtph264depay ! h264parse ! avdec_h264 ! autovideosink fps-update-interval=1000 sync=false";

        String messageRAW = "udpsrc port=" + portAudio + " ! audio/x-raw, format=S16LE, channels=1, rate=16000 ! autoaudiosink sync=false";
        String messageFLAC = "udpsrc port=" + portAudio + " ! flacparse ! flacdec ! autoaudiosink sync=false";

        /* shows different message depending on preferences */
        String messageAudio = flacEncoding ? messageFLAC : messageRAW;
        final String message = packetization ? messageVideoRTP : messageVideo + (streamAudio ? " " + messageAudio : "");

        new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_info)
                .setTitle(getResources().getString(R.string.usage_title))
                .setMessage(message)
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.copy, new Dialog.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("command", message);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(main, getResources().getString(R.string.copied), Toast.LENGTH_LONG).show();
                    }
                })
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
        //saves preference so that dialog doesn't show again.
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
