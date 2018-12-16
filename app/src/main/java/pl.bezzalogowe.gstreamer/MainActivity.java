package pl.bezzalogowe.gstreamer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.freedesktop.gstreamer.GStreamer;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("tutorial_udpsink");
        nativeClassInit();
    }

    private long native_custom_data;      // Native code will use this to keep private data
    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING

    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks

    private native void nativeInit();     // Initialize native code, build pipeline, etc

    private native void nativeFinalize(); // Destroy pipeline and shutdown native code

    private native void nativeStreamStart(char one, char two, char three, char four); // Set pipeline to PLAYING

    private native void nativeStreamStop(); // Set pipeline to PAUSED

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= 23) {
            if (this.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                this.requestPermissions(new String[]{Manifest.permission.CAMERA}, 200);
                return;
            }
        }

        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final EditText addressEditText = (EditText) this.findViewById(R.id.address);

        ImageButton stream_start = (ImageButton) this.findViewById(R.id.button_stream_start);
        stream_start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String[] tokens = addressEditText.getText().toString().split("[.,:]");
                byte[] numbers = new byte[]{-128, -128, -128, -128};
                if (0 < tokens.length && tokens.length <= 4) {
                    for (int i = 0; i < tokens.length; i++) {
                        if (Byte.MIN_VALUE <= (Integer.valueOf(tokens[i]) - 128) && (Integer.valueOf(tokens[i]) - 128) <= Byte.MAX_VALUE) {
                            numbers[i] = (byte) (Integer.valueOf(tokens[i]) - 128);
                        } else {
                            break;
                        }
                    }

                    addressEditText.setText((numbers[0] + 128) + "." + (numbers[1] + 128) + "." + (numbers[2] + 128) + "." + (numbers[3] + 128));
                    nativeStreamStart((char) numbers[0], (char) numbers[1], (char) numbers[2], (char) numbers[3]);
                } else {
                    addressEditText.setText("0.0.0.0");
                }
            }
        });

        ImageButton stream_stop = (ImageButton) this.findViewById(R.id.button_stream_stop);
        stream_stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                nativeStreamStop();
            }
        });

        // Start with disabled buttons, until native code is initialized
        this.findViewById(R.id.button_stream_start).setEnabled(false);
        this.findViewById(R.id.button_stream_stop).setEnabled(false);
        nativeInit();
    }

    protected void onSaveInstanceState(Bundle outState) {
    }

    protected void onDestroy() {
        nativeFinalize();
        super.onDestroy();
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

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized() {
        // Re-enable buttons, now that GStreamer is initialized
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            public void run() {
                activity.findViewById(R.id.button_stream_start).setEnabled(true);
                activity.findViewById(R.id.button_stream_stop).setEnabled(true);
            }
        });
    }
}
