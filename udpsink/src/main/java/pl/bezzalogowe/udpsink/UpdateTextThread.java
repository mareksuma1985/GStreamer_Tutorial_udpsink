package pl.bezzalogowe.udpsink;

import android.widget.TextView;

public class UpdateTextThread implements Runnable {
    private TextView view;
    private String msg;

    public UpdateTextThread(TextView element, String str) {
        this.view = element;
        this.msg = str;
    }

    @Override
    public void run() {
        view.setText(msg);
    }
}
