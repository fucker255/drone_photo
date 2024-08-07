package com.dji.FPVDemo;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.flightcontroller.Attitude;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.gimbal.GimbalState;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.useraccount.UserAccountManager;


public class MainActivity extends Activity implements SurfaceTextureListener, OnClickListener {

    private static final String TAG = MainActivity.class.getName();
    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    protected TextureView mVideoSurface = null;
    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;

    private Handler handler;
    private Runnable runnable;
    private File logFile;


    private FlightController mFlightController;
    private Gimbal gimbal = null;

    private int currentGimbalId = 0;

    private double droneLocationLat = 181, droneLocationLng = 181;
    private double droneLocationAlt = 0;
    private Attitude droneAttitude = new Attitude(0, 0, 0);
    private dji.common.gimbal.Attitude gimbalAttitude = new dji.common.gimbal.Attitude(0, 0, 0);


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());

        // 初始化或打开txt文件
        initLogFile();
        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        Camera camera = FPVDemoApplication.getCameraInstance();
        initFlightController();
        initGimbal();

        if (camera != null) {

            camera.setSystemStateCallback(new SystemState.Callback() {
                @Override
                public void onUpdate(SystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording) {
                                    recordingTime.setVisibility(View.VISIBLE);
                                } else {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });

        }

    }

    protected void onProductChange() {
        initPreviewer();
        loginAccount();
    }

    private void loginAccount() {

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                    }

                    @Override
                    public void onFailure(DJIError error) {
                        showToast("Login Error:"
                                + error.getDescription());
                    }
                });
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        onProductChange();

        if (mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view) {
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        super.onDestroy();
    }

    private void initUI() {
        // init mVideoSurface
        mVideoSurface = (TextureView) findViewById(R.id.video_previewer_surface);

        recordingTime = (TextView) findViewById(R.id.timer);
        mCaptureBtn = (Button) findViewById(R.id.btn_capture);
        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mShootPhotoModeBtn = (Button) findViewById(R.id.btn_shoot_photo_mode);
        mRecordVideoModeBtn = (Button) findViewById(R.id.btn_record_video_mode);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }

        mCaptureBtn.setOnClickListener(this);
        mRecordBtn.setOnClickListener(this);
        mShootPhotoModeBtn.setOnClickListener(this);
        mRecordVideoModeBtn.setOnClickListener(this);

        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });
    }

    private void initPreviewer() {

        BaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
            }
        }
    }

    private void uninitPreviewer() {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            // Reset the callback
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG, "onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btn_capture:
                captureAction();
                break;
            case R.id.btn_shoot_photo_mode:
                if (isMavicAir2() || isM300()) {
                    switchCameraFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE);
                } else {
                    switchCameraMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO);
                }
                break;
            case R.id.btn_record_video_mode:
                if (isMavicAir2() || isM300()) {
                    switchCameraFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL);
                } else {
                    switchCameraMode(SettingsDefinitions.CameraMode.RECORD_VIDEO);
                }
                break;
            default:
                break;
        }
    }

    private void switchCameraFlatMode(SettingsDefinitions.FlatCameraMode flatCameraMode) {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.setFlatMode(flatCameraMode, error -> {
                if (error == null) {
                    showToast("Switch Camera Flat Mode Succeeded");
                } else {
                    showToast(error.getDescription());
                }
            });
        }
    }

    private void switchCameraMode(SettingsDefinitions.CameraMode cameraMode) {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.setMode(cameraMode, error -> {
                if (error == null) {
                    showToast("Switch Camera Mode Succeeded");
                } else {
                    showToast(error.getDescription());
                }
            });
        }
    }

    // Method for taking photo
    private void captureAction() {
        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            if (isMavicAir2() || isM300()) {
                camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, djiError -> {
                    if (null == djiError) {
                        takePhoto();
                    }
                });
            } else {
                camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, djiError -> {
                    if (null == djiError) {
                        takePhoto();
                    }
                });
            }
        }
    }

    private void takePhoto() {
        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                camera.startShootPhoto(djiError -> {
                    if (djiError == null) {
                        showToast("take photo: success");
                    } else {
                        showToast(djiError.getDescription());
                    }
                });
            }
        }, 2000);
    }

    // Method for starting recording
    private void startRecord() {
        showToast("start recording: success");
        runnable = new Runnable() {
            @Override
            public void run() {
                // 拍照并获取无人机信息
                takePhotoAndLogData();
                // 每2秒执行一次
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(runnable);
    }

    private void takePhotoAndLogData() {
        // 这里添加拍照代码
        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera == null) {
            return;
        }
        camera.startShootPhoto(djiError -> {
            if (djiError == null) {

            } else {
                showToast(djiError.getDescription());
            }
        });
        // 获取无人机的经纬度、姿态和当前时间

        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        // 将信息记录到txt文件中
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.append(currentTime)
                    .append(", ")
                    .append(String.valueOf(droneLocationLat))
                    .append(", ")
                    .append(String.valueOf(droneLocationLng))
                    .append(", ")
                    .append(String.valueOf(droneLocationAlt))
                    .append(", ")
                    .append(String.valueOf(droneAttitude.pitch))
                    .append(", ")
                    .append(String.valueOf(droneAttitude.roll))
                    .append(", ")
                    .append(String.valueOf(droneAttitude.yaw))
                    .append(", ")
                    .append(String.valueOf(gimbalAttitude.getPitch()))
                    .append(", ")
                    .append(String.valueOf(gimbalAttitude.getRoll()))
                    .append(", ")
                    .append(String.valueOf(gimbalAttitude.getYaw()))
                    .append("\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Method for stopping recording
    private void stopRecord() {

//        Camera camera = FPVDemoApplication.getCameraInstance();
//        if (camera != null) {
//            camera.stopRecordVideo(djiError -> {
//                if (djiError == null) {
//                    showToast("Stop recording: success");
//                } else {
//                    showToast(djiError.getDescription());
//                }
//            }); // Execute the stopRecordVideo API
//        }
        showToast("stop recording: success");

        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    private void initFlightController() {

        BaseProduct product = FPVDemoApplication.getProductInstance();
        if (product != null && product.isConnected()) {
            if (product instanceof Aircraft) {
                mFlightController = ((Aircraft) product).getFlightController();
            }
        }

        if (mFlightController != null) {

            mFlightController.setStateCallback(
                    new FlightControllerState.Callback() {
                        @Override
                        public void onUpdate(@NonNull FlightControllerState
                                                     djiFlightControllerCurrentState) {
                            droneLocationLat = djiFlightControllerCurrentState.getAircraftLocation().getLatitude();
                            droneLocationLng = djiFlightControllerCurrentState.getAircraftLocation().getLongitude();
                            droneLocationAlt = djiFlightControllerCurrentState.getAircraftLocation().getAltitude();
                            droneAttitude = djiFlightControllerCurrentState.getAttitude();
                        }
                    });

        }
    }


    private void initGimbal() {
        BaseProduct product = FPVDemoApplication.getProductInstance();
        if (product != null) {
            if (product instanceof Aircraft) {
                gimbal = ((Aircraft) product).getGimbals().get(currentGimbalId);
            } else {
                gimbal = product.getGimbal();
            }
        }

        if (gimbal != null) {

            gimbal.setStateCallback(
                    new GimbalState.Callback() {
                        @Override
                        public void onUpdate(@NonNull GimbalState
                                                     djiGimbalCurrentState) {
                            gimbalAttitude = djiGimbalCurrentState.
                                    getAttitudeInDegrees();

                        }
                    });

        }
    }

    private void initLogFile() {
        File dir = new File(getExternalFilesDir(null), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        logFile = new File(dir, "drone_log.txt");
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
                try (FileWriter writer = new FileWriter(logFile, true)) {
                    writer.append("currentTime")
                            .append(", ")
                            .append("latitude")
                            .append(", ")
                            .append("longitude")
                            .append(", ")
                            .append("attitude")
                            .append(", ")
                            .append("drone_pitch")
                            .append(", ")
                            .append("drone_roll")
                            .append(", ")
                            .append("drone_yaw")
                            .append(", ")
                            .append("gimbal_pitch")
                            .append(", ")
                            .append("gimbal_roll")
                            .append(", ")
                            .append("gimbal_yaw")
                            .append("\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private boolean isMavicAir2() {
        BaseProduct baseProduct = FPVDemoApplication.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MAVIC_AIR_2;
        }
        return false;
    }

    private boolean isM300() {
        BaseProduct baseProduct = FPVDemoApplication.getProductInstance();
        if (baseProduct != null) {
            return baseProduct.getModel() == Model.MATRICE_300_RTK;
        }
        return false;
    }
}
