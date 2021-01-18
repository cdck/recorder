package com.xlk.screenrecoder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodecInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.Build.VERSION_CODES.M;

public class MainActivity extends AppCompatActivity {

    private final int REQUEST_MEDIA_PROJECTION = 1;
    private final int REQUEST_PERMISSIONS = 2;
    private MediaProjectionManager mMediaProjectionManager;
    private MediaCodecInfo[] mAvcCodecInfos;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ScreenRecorder mRecorder;
    private Button mButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mButton = findViewById(R.id.button);
        mMediaProjectionManager = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
        mAvcCodecInfos = Utils.findEncodersByType(ScreenRecorder.VIDEO_AVC);

    }

    public void clickStart(View view) {
        if (mRecorder != null) {
            stopRecordingAndOpenFile(view.getContext());
        } else {
            if (hasPermissions()) {
                if (mMediaProjection == null) {
                    requestMediaProjection();
                } else {
                    startCapturing(mMediaProjection);
                }
            } else {
                requestPermissions();
            }
        }
    }

    @TargetApi(M)
    private void requestPermissions() {
        String[] permissions = new String[]{WRITE_EXTERNAL_STORAGE};
        requestPermissions(permissions, REQUEST_PERMISSIONS);
    }

    private boolean hasPermissions() {
        PackageManager pm = getPackageManager();
        String packageName = getPackageName();
        int granted = PackageManager.PERMISSION_GRANTED | pm.checkPermission(WRITE_EXTERNAL_STORAGE, packageName);
        return granted == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaProjection() {
        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean all = true;
            for (int grant : grantResults) {
                if (grant != PackageManager.PERMISSION_GRANTED) {
                    all = false;
                }
            }
            if (all) {
                requestMediaProjection();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e("@@", "media projection is null");
                return;
            }
            mMediaProjection = mediaProjection;
            mMediaProjection.registerCallback(mProjectionCallback, new Handler());
            startCapturing(mediaProjection);
        }
    }

    private void startCapturing(MediaProjection mediaProjection) {
        Log.i("mylog", "startCapturing");
        VideoEncodeConfig video = createVideoConfig();
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            cancelRecorder();
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        final File file = new File(dir, "Screenshots-" + format.format(new Date())
                + "-" + video.width + "x" + video.height + ".mp4");
        mRecorder = newRecorder(mediaProjection, video, file);
        if (hasPermissions()) {
            startRecorder();
        } else {
            cancelRecorder();
        }
    }

    private ScreenRecorder newRecorder(MediaProjection mediaProjection, VideoEncodeConfig video,
                                       File output) {
        final VirtualDisplay display = getOrCreateVirtualDisplay(mediaProjection, video);
        ScreenRecorder r = new ScreenRecorder(video, display, output.getAbsolutePath());
        r.setCallback(new ScreenRecorder.Callback() {
            long startTime = 0;

            @Override
            public void onStop(Throwable error) {
                runOnUiThread(() -> stopRecorder());
                if (error != null) {
                    error.printStackTrace();
                    output.delete();
                }
//                else {
//                    Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
//                            .addCategory(Intent.CATEGORY_DEFAULT)
//                            .setData(Uri.fromFile(output));
//                    sendBroadcast(intent);
//                }
            }

            @Override
            public void onStart() {
            }

            @Override
            public void onRecording(long presentationTimeUs) {
            }
        });
        return r;
    }
    private VirtualDisplay getOrCreateVirtualDisplay(MediaProjection mediaProjection, VideoEncodeConfig config) {
        if (mVirtualDisplay == null) {
            mVirtualDisplay = mediaProjection.createVirtualDisplay("ScreenRecorderThread-display0",
                    config.width, config.height, 1 /*dpi*/,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    null /*surface*/, null, null);
        } else {
            // resize if size not matched
            Point size = new Point();
            mVirtualDisplay.getDisplay().getSize(size);
            if (size.x != config.width || size.y != config.height) {
                mVirtualDisplay.resize(config.width, config.height, 1);
            }
        }
        return mVirtualDisplay;
    }

    private void cancelRecorder() {
        Log.e("mylog", "cancelRecorder");
        if (mRecorder == null) {
            return;
        }
        stopRecorder();
    }

    private VideoEncodeConfig createVideoConfig() {
        MediaCodecInfo codec = mAvcCodecInfos.length > 0 ? mAvcCodecInfos[0] : null;
        if (codec == null) {
            return null;
        }
        Log.i("mylog", "编码器=" + codec.getName());
        MediaCodecInfo.CodecCapabilities capabilities = codec.getCapabilitiesForType(ScreenRecorder.VIDEO_AVC);
        // video size

        WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            wm.getDefaultDisplay().getRealSize(point);
        } else {
            wm.getDefaultDisplay().getSize(point);
        }

        int width = point.x;
        int height = point.y;
        int framerate = 18;
        int iframe = 2;
        int bitrate =
                point.x * point.y * 1000;
//                1000 * 1000;
        MediaCodecInfo.CodecProfileLevel profileLevel = capabilities.profileLevels[0];
        return new VideoEncodeConfig(width, height, bitrate,
                framerate, iframe, codec.getName(), ScreenRecorder.VIDEO_AVC, profileLevel);

    }

    private MediaProjection.Callback mProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            if (mRecorder != null) {
                stopRecorder();
            }
        }
    };

    private void startRecorder() {
        Log.i("mylog", "startRecorder");
        if (mRecorder == null) {
            return;
        }
        mRecorder.start();
        mButton.setText("停止录制");
        registerReceiver(mStopActionReceiver, new IntentFilter(ACTION_STOP));
    }

    private void stopRecorder() {
        Log.i("mylog", "stopRecorder");
        if (mRecorder != null) {
            mRecorder.quit();
        }
        mRecorder = null;
        mButton.setText("开始录制");
        try {
            unregisterReceiver(mStopActionReceiver);
        } catch (Exception e) {
            //ignored
        }
    }

    static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".action.STOP";

    private BroadcastReceiver mStopActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                stopRecordingAndOpenFile(context);
            }
        }
    };

    private void stopRecordingAndOpenFile(Context context) {
        Log.i("mylog", "stopRecordingAndOpenFile");
        File file = new File(mRecorder.getSavedPath());
        stopRecorder();
        StrictMode.VmPolicy vmPolicy = StrictMode.getVmPolicy();
        try {
            // disable detecting FileUriExposure on public file
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
            viewResult(file);
        } finally {
            StrictMode.setVmPolicy(vmPolicy);
        }
    }

    private void viewResult(File file) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.addCategory(Intent.CATEGORY_DEFAULT);
        view.setDataAndType(Uri.fromFile(file), ScreenRecorder.VIDEO_AVC);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            // no activity can open this video
        }
    }
}