package com.dji.videostreamdecodingsample;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder;
import com.dji.videostreamdecodingsample.media.NativeHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import dji.common.airlink.PhysicalSource;
import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.simulator.InitializationData;
import dji.common.flightcontroller.simulator.SimulatorState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.model.LocationCoordinate2D;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.sdk.airlink.OcuSyncLink;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.afinal.core.AsyncTask;
import dji.common.flightcontroller.FlightControllerState;

public class MainActivity extends Activity implements DJICodecManager.YuvDataCallback {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;
    private SurfaceHolder.Callback surfaceCallback;

    private enum DemoType {USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER}

    private static DemoType demoType = DemoType.USE_TEXTURE_VIEW;
    private VideoFeeder.VideoFeed standardVideoFeeder;


    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;
    private TextView titleTv;
    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    //Simulator stuff
    private FlightController mFlightController;
    private ToggleButton mBtnSimulator;
    private Button mBtnTakeOff;
    private Button mBtnLand;
    private ToggleButton mSwtcEnableVirtualStick;
    private TextView mTextView;
    private TextView target_tv;

    private float x;
    private float y;

    private float mPitch;
    private float mRoll;
    private float mYaw;
    private float mThrottle;

    //Video stream stuff
    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private List<Camera> mCameras = new ArrayList<>();
    private DJICodecManager mCodecManager;
    //private TextView savePath;
    private ToggleButton screenShot;
    private StringBuilder stringBuilder;
    private int videoViewWidth;
    private int videoViewHeight;
    private String ip_address;
    private int thermal_visual=0;
    private Button thermalVisualButton;
    private FlightControllerState mFlightControllerState;
    private byte[] byteArrayImage;
    private LinkedList imagesList = new LinkedList();
    private int linkedListSize=0;
    private int qualityImage=10;
    private ToggleButton mMotorsButton;
    private boolean sendingFrame=false;
    private SocketClient imgClient;


    @Override
    protected void onResume() {
        super.onResume();
        initSurfaceOrTextureView();
        notifyStatusChange();
        initFlightController();
    }

    private void initSurfaceOrTextureView() {
        switch (demoType) {
            case USE_SURFACE_VIEW:
                initPreviewerSurfaceView();
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView();

                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView();
                break;
            case USE_TEXTURE_VIEW:
                initPreviewerTextureView();
                break;
        }
    }

    @Override
    protected void onPause() {
        if (mCameras.get(1) != null) {
            if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mReceivedVideoDataListener);
            }
            if (standardVideoFeeder != null) {
                standardVideoFeeder.removeVideoDataListener(mReceivedVideoDataListener);
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager.destroyCodec();
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        initUi();
        Intent intent = getIntent();
        byte[] ipByte = intent.getByteArrayExtra("ip_address");
        ip_address=new String(ipByte);
        Log.d("MY TAG",ip_address);

        // Thread per gestire i msg del socket
        Thread myThread = new Thread(new MyServerThread());
        ClientThread sendMsg = new ClientThread(ip_address,8081);
        //SendImgThread sendImg = new SendImgThread(ip_address,8888);
        imgClient = new SocketClient(ip_address,8888);

        if (isM300Product()) {
            OcuSyncLink ocuSyncLink = VideoDecodingApplication.getProductInstance().getAirLink().getOcuSyncLink();
            // If your MutltipleLensCamera is set at right or top, you need to change the PhysicalSource to RIGHT_CAM or TOP_CAM.
            ocuSyncLink.assignSourceToPrimaryChannel(PhysicalSource.LEFT_CAM, PhysicalSource.FPV_CAM, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("assignSourceToPrimaryChannel success.");
                    } else {
                        showToast("assignSourceToPrimaryChannel fail, reason: " + error.getDescription());
                    }
                }
            });
        }

        myThread.start();
        sendMsg.start();
        //sendImg.start();
    }

    public static boolean isM300Product() {
        if (DJISDKManager.getInstance().getProduct() == null) {
            return false;
        }
        Model model = DJISDKManager.getInstance().getProduct().getModel();
        return model == Model.MATRICE_300_RTK;
    }

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

    // iniz. il controllore di volo
    private void initFlightController() {

        Aircraft aircraft = DJISimulatorApplication.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) { // check if the aircraft is not null and is connected
            showToast("Disconnected");
            mFlightController = null;
            return;
        } else {
            mFlightController = aircraft.getFlightController(); // ottengo oggetto per controllare il drone
            mFlightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
            mFlightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
            mFlightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
            mFlightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

            // callback per il simulatore che ad ogni aggiornamento degli stati li printa tramite
            // textView
            mFlightController.getSimulator().setStateCallback(new SimulatorState.Callback() {
                @Override
                public void onUpdate(final SimulatorState stateData) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {

                            String yaw = String.format("%.2f", stateData.getYaw());
                            String pitch = String.format("%.2f", stateData.getPitch());
                            String roll = String.format("%.2f", stateData.getRoll());
                            String positionX = String.format("%.2f", stateData.getPositionX());
                            String positionY = String.format("%.2f", stateData.getPositionY());
                            String positionZ = String.format("%.2f", stateData.getPositionZ());

                            x = stateData.getPositionX();
                            y = stateData.getPositionY();

                            mTextView.setText("Yaw : " + yaw + ", Pitch : " + pitch + ", Roll : " + roll + "\n" + "PosX : " + positionX +
                                    ", PosY : " + positionY +
                                    ", PosZ : " + positionZ);
                        }
                    });
                }
            });
        }
    }

    private void initUi() {
        mBtnTakeOff = (Button) findViewById(R.id.btn_take_off); // take off
        mBtnLand = (Button) findViewById(R.id.btn_land); // land
        mBtnSimulator = (ToggleButton) findViewById(R.id.btn_start_simulator); // per fare tutto in simulazione
        mTextView = (TextView) findViewById(R.id.textview_simulator); // element to show the simulator state infos
        mSwtcEnableVirtualStick = (ToggleButton) findViewById(R.id.swtc_enable_virtual_stick); //enable/disable Virtual Control Mode
        target_tv = (TextView) findViewById(R.id.target_ttv);
        thermalVisualButton=(Button) findViewById(R.id.mythermalVisualButton);
        mMotorsButton = (ToggleButton) findViewById(R.id.btnMotors);

        //savePath = (TextView) findViewById(R.id.activity_main_save_path);
        screenShot = (ToggleButton) findViewById(R.id.activity_main_screen_shot);
        //savePath.setVisibility(View.INVISIBLE);

        titleTv = (TextView) findViewById(R.id.title_tv);

        mBtnTakeOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mFlightController != null) {
                    mFlightController.startTakeoff(
                            new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError != null) {
                                        showToast(djiError.getDescription());
                                    } else {
                                        showToast("Take off Success");
                                    }
                                }
                            }
                    );
                }
            }
        });

        thermalVisualButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                change_display();
            }
        });

        mBtnLand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mFlightController != null) {

                    mFlightController.startLanding(
                            new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError djiError) {
                                    if (djiError != null) {
                                        showToast(djiError.getDescription());
                                    } else {
                                        showToast("Start Landing");
                                    }
                                }
                            }
                    );

                }
            }
        });

        videostreamPreviewTtView = (TextureView) findViewById(R.id.livestream_preview_ttv);
        videostreamPreviewSf = (SurfaceView) findViewById(R.id.livestream_preview_sf);
        videostreamPreviewSf.setClickable(false);
        videostreamPreviewSf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float rate = VideoFeeder.getInstance().getTranscodingDataRate();
                showToast("current rate:" + rate + "Mbps");
                if (rate < 10) {
                    VideoFeeder.getInstance().setTranscodingDataRate(10.0f);
                    showToast("set rate to 10Mbps");
                } else {
                    VideoFeeder.getInstance().setTranscodingDataRate(3.0f);
                    showToast("set rate to 3Mbps");
                }
            }
        });

        // Setta parametri iniziali della simulazione (starting point ....)
        mBtnSimulator.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {

                    mTextView.setVisibility(View.VISIBLE);

                    if (mFlightController != null) {

                        mFlightController.getSimulator()
                                .start(InitializationData.createInstance(new LocationCoordinate2D(23, 113), 10, 10),
                                        new CommonCallbacks.CompletionCallback() {
                                            @Override
                                            public void onResult(DJIError djiError) {
                                                if (djiError != null) {
                                                    showToast(djiError.getDescription());
                                                } else {
                                                    showToast("Start Simulator Success");
                                                }
                                            }
                                        });
                    }

                } else {

                    mTextView.setVisibility(View.INVISIBLE);

                    if (mFlightController != null) {
                        mFlightController.getSimulator()
                                .stop(new CommonCallbacks.CompletionCallback() {
                                          @Override
                                          public void onResult(DJIError djiError) {
                                              if (djiError != null) {
                                                  showToast(djiError.getDescription());
                                              } else {
                                                  showToast("Stop Simulator Success");
                                              }
                                          }
                                      }
                                );
                    }
                }
            }
        });

        mMotorsButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    mFlightController.turnOnMotors(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                showToast(djiError.getDescription());
                            } else {
                                showToast("Motors On");
                            }
                        }
                    });
                } else {
                    mFlightController.turnOffMotors(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                showToast(djiError.getDescription());
                            } else {
                                showToast("Motors Off");
                            }
                        }
                    });
                }
            }
        });

        screenShot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                handleYUVClick();
                if (isChecked) {
                    videostreamPreviewTtView.setVisibility(View.INVISIBLE);
                    videostreamPreviewSf.setVisibility(View.INVISIBLE);
                    showToast("Start Streaming Success");
                } else {
                    videostreamPreviewTtView.setVisibility(View.VISIBLE);
                    videostreamPreviewSf.setVisibility(View.INVISIBLE);
                    showToast("Stop Streaming Success");
                }
            }
        });
        //Enable/disable of virtual control mode
        mSwtcEnableVirtualStick.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mFlightController != null) {
                    mFlightController.setVirtualStickModeEnabled(isChecked, new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError djiError) {
                            if (djiError != null) {
                                showToast(djiError.getDescription());
                            } else {
                                if (mSwtcEnableVirtualStick.isChecked()) {
                                    showToast("VCM Enable Success");
                                } else {
                                    showToast("VCM Disable Success");
                                }
                            }
                        }
                    });
                }
            }
        });
        updateUIVisibility();
    }


    private void updateUIVisibility() {
        switch (demoType) {
            case USE_SURFACE_VIEW:
                videostreamPreviewSf.setVisibility(View.VISIBLE);
                videostreamPreviewTtView.setVisibility(View.GONE);
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 * we need display two video stream at the same time, so we need let them to be visible.
                 */
                videostreamPreviewSf.setVisibility(View.VISIBLE);
                videostreamPreviewTtView.setVisibility(View.VISIBLE);
                break;

            case USE_TEXTURE_VIEW:
                videostreamPreviewSf.setVisibility(View.GONE);
                videostreamPreviewTtView.setVisibility(View.VISIBLE);
                break;
        }
    }

    private long lastupdate;

    private void notifyStatusChange() {

        final BaseProduct product = VideoDecodingApplication.getProductInstance();

        Log.d(TAG, "notifyStatusChange: " + (product == null ? "Disconnect" : (product.getModel() == null ? "null model" : product.getModel().name())));
        if (product != null && product.isConnected() && product.getModel() != null) {
            updateTitle(product.getModel().name() + " Connected " + demoType.name());
        } else {
            updateTitle("Disconnected");
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(TAG, "camera recv video data size: " + size);
                    lastupdate = System.currentTimeMillis();
                }
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        /**
                         we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                        break;

                    case USE_TEXTURE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                }

            }
        };

        if (null == product || !product.isConnected()) {
            mCameras = null;
            showToast("Disconnected");
        } else {
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                mCameras = product.getCameras();
                mCameras.get(1).setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            showToast("can't change mode of camera, error:" + djiError.getDescription());
                        }
                    }
                });

                mCameras.get(1).setDisplayMode(SettingsDefinitions.DisplayMode.VISUAL_ONLY,new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            showToast("can't change Display mode, error:" + djiError.getDescription());
                        }
                    }
                });

                //When calibration is needed or the fetch key frame is required by SDK, should use the provideTranscodedVideoFeed
                //to receive the transcoded video feed from main camera.
                if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER && isTranscodedVideoFeedNeeded()) {
                    standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed();
                    standardVideoFeeder.addVideoDataListener(mReceivedVideoDataListener);
                    return;
                }
                if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                    VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
                }

            }
        }
    }

    public void onClick(View v) {

    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private void initPreviewerTextureView() {
        videostreamPreviewTtView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable: width " + videoViewWidth + " height " + videoViewHeight);
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager.resetKeyFrame();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable2: width " + videoViewWidth + " height " + videoViewHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCodecManager != null) {
                    mCodecManager.cleanSurface();
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private void initPreviewerSurfaceView() {
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = videostreamPreviewSf.getWidth();
                videoViewHeight = videostreamPreviewSf.getHeight();
                Log.d(TAG, "real onSurfaceTextureAvailable3: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager == null) {
                            mCodecManager = new DJICodecManager(getApplicationContext(), holder, videoViewWidth,
                                    videoViewHeight);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.getInstance().init();
                        DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), holder.getSurface());
                        DJIVideoStreamDecoder.getInstance().resume();
                        break;
                }

            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable4: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        //mCodecManager.onSurfaceSizeChanged(videoViewWidth, videoViewHeight, 0);
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
                        break;
                }

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.cleanSurface();
                            mCodecManager.destroyCodec();
                            mCodecManager = null;
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().stop();
                        NativeHelper.getInstance().release();
                        break;
                }

            }
        };

        videostreamPreviewSh.addCallback(surfaceCallback);
    }


    @Override
    public void onYuvDataReceived(MediaFormat format, final ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
        //In this demo, we test the YUV data by saving it into JPG files.
        //DJILog.d(TAG, "onYuvDataReceived " + dataSize);

        if ( yuvFrame != null & sendingFrame==false) {
            sendingFrame=true;
            //linkedListSize=linkedListSize+1;
            final byte[] bytes = new byte[dataSize];
            yuvFrame.get(bytes);

            //DJILog.d(TAG, "onYuvDataReceived2 " + dataSize);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    // two samples here, it may has other color format.
                    int colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    switch (colorFormat) {
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                            //NV12
                            if (Build.VERSION.SDK_INT <= 23) {
                                oldSaveYuvDataToJPEG(bytes, width, height);
                            } else {
                                newSaveYuvDataToJPEG(bytes, width, height);
                            }
                            break;
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                            //YUV420P
                            newSaveYuvDataToJPEG420P(bytes, width, height);
                            break;
                        default:
                            break;
                    }

                }
            });
        }
    }

    private void change_display() {

        if(thermal_visual==2){
            mCameras.get(1).setDisplayMode(SettingsDefinitions.DisplayMode.VISUAL_ONLY,new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        showToast("can't change Display mode in visual, error:" + djiError.getDescription());
                    }
                }
            });
            thermal_visual=0;
        }
        else if(thermal_visual==0){
            mCameras.get(1).setDisplayMode(SettingsDefinitions.DisplayMode.THERMAL_ONLY,new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        showToast("can't change Display mode in thermal, error:" + djiError.getDescription());
                    }
                }
            });
            thermal_visual=1;
        }
        else {
            mCameras.get(1).setDisplayMode(SettingsDefinitions.DisplayMode.MSX,new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        showToast("can't change Display mode in thermal, error:" + djiError.getDescription());
                    }
                }
            });
            thermal_visual=2;
        }
    }

    // For android API <= 23
    private void oldSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return;
        }

        byte[] y = new byte[width * height];
        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        byte[] nu = new byte[width * height / 4]; //
        byte[] nv = new byte[width * height / 4];

        System.arraycopy(yuvFrame, 0, y, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[y.length + 2 * i];
            u[i] = yuvFrame[y.length + 2 * i + 1];
        }
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        for (int j = 0; j < uvWidth / 2; j++) {
            for (int i = 0; i < uvHeight / 2; i++) {
                byte uSample1 = u[i * uvWidth + j];
                byte uSample2 = u[i * uvWidth + j + uvWidth / 2];
                byte vSample1 = v[(i + uvHeight / 2) * uvWidth + j];
                byte vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2];
                nu[2 * (i * uvWidth + j)] = uSample1;
                nu[2 * (i * uvWidth + j) + 1] = uSample1;
                nu[2 * (i * uvWidth + j) + uvWidth] = uSample2;
                nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2;
                nv[2 * (i * uvWidth + j)] = vSample1;
                nv[2 * (i * uvWidth + j) + 1] = vSample1;
                nv[2 * (i * uvWidth + j) + uvWidth] = vSample2;
                nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2;
            }
        }
        //nv21test
        byte[] bytes = new byte[yuvFrame.length];
        System.arraycopy(y, 0, bytes, 0, y.length);
        for (int i = 0; i < u.length; i++) {
            bytes[y.length + (i * 2)] = nv[i];
            bytes[y.length + (i * 2) + 1] = nu[i];
        }
        Log.d(TAG,
                "onYuvDataReceived: frame index: "
                        + DJIVideoStreamDecoder.getInstance().frameIndex
                        + ",array length: "
                        + bytes.length);
        screenShot(bytes, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    private void newSaveYuvDataToJPEG(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return;
        }
        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];
        for (int i = 0; i < u.length; i++) {
            v[i] = yuvFrame[length + 2 * i];
            u[i] = yuvFrame[length + 2 * i + 1];
        }
        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = u[i];
            yuvFrame[length + 2 * i + 1] = v[i];
        }
        screenShot(yuvFrame, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    private void newSaveYuvDataToJPEG420P(byte[] yuvFrame, int width, int height) {
        if (yuvFrame.length < width * height) {
            return;
        }
        int length = width * height;

        byte[] u = new byte[width * height / 4];
        byte[] v = new byte[width * height / 4];

        for (int i = 0; i < u.length; i++) {
            u[i] = yuvFrame[length + i];
            v[i] = yuvFrame[length + u.length + i];
        }
        for (int i = 0; i < u.length; i++) {
            yuvFrame[length + 2 * i] = v[i];
            yuvFrame[length + 2 * i + 1] = u[i];
        }
        screenShot(yuvFrame, Environment.getExternalStorageDirectory() + "/DJI_ScreenShot", width, height);
    }

    /**
     * Save the buffered data into a JPG image file
     */
    private void screenShot(byte[] buf, String shotDir, int width, int height) {

        YuvImage yuvImage = new YuvImage(buf,
                ImageFormat.NV21,
                width,
                height,
                null);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Rect rect = new Rect(0, 0, width, height);
        yuvImage.compressToJpeg(rect, qualityImage, bos);
        /*
        byte[] bmp = byteArrayOutputStream.toByteArray();

        // convert to Bitmap and scale the image
        Bitmap bitmap = getScaledImage(bmp, width, height, 1);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, bos);
         */
        //UDP limit is 64kb

        if(bos.size()>62000){
            if(qualityImage>11) {
                qualityImage = qualityImage - 10;
            }
            //linkedListSize=linkedListSize-1;
        }
        else {
            byteArrayImage = bos.toByteArray();
            //imagesList.addLast(byteArrayImage);
            imgClient.execute(byteArrayImage);

            if(bos.size()<50000){
                if(qualityImage<100){
                    qualityImage=qualityImage+1;
                }
            }
            if(bos.size()>51000){
                if(qualityImage>1){
                    qualityImage=qualityImage-1;
                }
            }
        }


        //Log.d("MY TAG","qualityImage: "+qualityImage);
        //Log.d("MY TAG","list size: "+linkedListSize+" actual size: "+imagesList.size());
        //Log.d("MY TAG","bos size: "+bos.size());

        try {
            // byteArrayOutputStream.flush();
            // byteArrayOutputStream.close();
            bos.flush();
            bos.reset();
            bos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //set max frame rate to 100 fps
        /*
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        */
        sendingFrame=false;
    }

    public static Bitmap getScaledImage(byte[] data, int width, int height, int scalingFactor) {
        BitmapFactory.Options bitmapFatoryOptions = new BitmapFactory.Options();
        bitmapFatoryOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, bitmapFatoryOptions);
        Bitmap scaledBmp = Bitmap.createScaledBitmap(bmp, width / scalingFactor, height / scalingFactor, true);
        return scaledBmp;
    }


    private void handleYUVClick() {
        if (screenShot.isSelected()) {
            screenShot.setText("YUV Screen Shot");
            screenShot.setSelected(false);

            switch (demoType) {
                case USE_SURFACE_VIEW:
                case USE_TEXTURE_VIEW:
                    mCodecManager.enabledYuvData(false);
                    mCodecManager.setYuvDataCallback(null);
                    // ToDo:
                    break;
                case USE_SURFACE_VIEW_DEMO_DECODER:
                    DJIVideoStreamDecoder.getInstance().changeSurface(videostreamPreviewSh.getSurface());
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(null);
                    break;
            }
            //savePath.setText("");
            //savePath.setVisibility(View.INVISIBLE);
            stringBuilder = null;
        } else {
            screenShot.setText("Live Stream");
            screenShot.setSelected(true);

            switch (demoType) {
                case USE_TEXTURE_VIEW:
                case USE_SURFACE_VIEW:
                    mCodecManager.enabledYuvData(true);
                    mCodecManager.setYuvDataCallback(this);
                    break;
                case USE_SURFACE_VIEW_DEMO_DECODER:
                    DJIVideoStreamDecoder.getInstance().changeSurface(null);
                    DJIVideoStreamDecoder.getInstance().setYuvDataListener(MainActivity.this);
                    break;
            }
            //savePath.setText("");
            //savePath.setVisibility(View.VISIBLE);
        }
    }

    private void displayPath(String path) {
        if (stringBuilder == null) {
            stringBuilder = new StringBuilder();
        }

        path = path + "\n";
        stringBuilder.append(path);
        //savePath.setText(stringBuilder.toString());
    }

    private boolean isTranscodedVideoFeedNeeded() {
        if (VideoFeeder.getInstance() == null) {
            return false;
        }

        return VideoFeeder.getInstance().isFetchKeyFrameNeeded() || VideoFeeder.getInstance()
                .isLensDistortionCalibrationNeeded();
    }

    //Socket Server
    class MyServerThread implements Runnable {
        Socket socket;
        ServerSocket server;
        InputStreamReader isr;
        BufferedReader buffer;
        String msg;

        // target position coordinates
        float targetX;
        float targetY;

        double euclideanDistance;

        JSONObject jObj;


        @Override
        public void run() {

            try {
                server = new ServerSocket(8080);

                while (true) {

                    // wait for a new message from the client
                    socket = server.accept();

                    // store the message and save the value in its field
                    isr = new InputStreamReader(socket.getInputStream());
                    buffer = new BufferedReader(isr);
                    msg = buffer.readLine();
                    jObj = new JSONObject(msg);

                    //We store the target position coordinates into different variables
                    mYaw = (float) jObj.getDouble("yaw");
                    mPitch = (float) jObj.getDouble("pitch");
                    mRoll = (float) jObj.getDouble("roll");
                    mThrottle =  (float) jObj.getDouble("throttle");

                    boolean virtualStickModeActive = mFlightController.isVirtualStickControlModeAvailable();

                    // The euclidean distance between the body frame of the drone and the target point is computed
                    // In this way is it possible to use it to compute the linear velocity along the Roll axis
                    // Pitch velocity is setted to zero
                    // We use atan2 to compute the angular velocity along the yaw axis inside computeAnguarVelocity to let the drone rotate
                    //euclideanDistance = distanceFromTargetPos(targetX, targetY);

                    //mPitch = 0;
                    //mRoll = (float)((1.5) *(computeLinearVelocity(euclideanDistance)));
                    //mYaw = 15 * computeAnguarVelocity(targetX, targetY);
                    //mThrottle = 0;
                    //mPitch = (float) ((0.5) * ((targetY) / (euclideanDistance)));
                    //mRoll = (float) ((0.5) * ((targetX) / (euclideanDistance)));
                    //mYaw = 0;
                    //mThrottle = 0;

                    //Each time a new target position is received the velocities are sent to the FlightController
                    FlightControlData data = new FlightControlData(mPitch, mRoll, mYaw, mThrottle);

                    if (mFlightController != null & virtualStickModeActive) {
                        // metodo che manda le variabili globali (Salvate nell'oggetto FlightControlData) al mFlightController
                        mFlightController.sendVirtualStickFlightControlData(data, new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError djiError) {}
                                }
                        );
                    }
                }

            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }

        //y is the horizontal side axis
        //x is the frontal axis
        public float computeAngularVelocity(float x, float y) {
            return (float) (((Math.PI / 2) - Math.atan2(targetX, targetY)) / Math.PI);
        }

        // The normal linear velocity is proportional to the distance and it is saturated to 1 if necessary.
        public float computeLinearVelocity(double dist) {
            return (float) satNormalVelocity(dist / 10);
        }

        public double satNormalVelocity(double vel) {
            if (vel > 1) {
                return 1;
            } else if (vel < -1) {
                return -1;
            }
            return vel;
        }

        // Euclidean distance between the body frame and the target position
        public double distanceFromTargetPos(float targX, float targY) {
            return Math.sqrt(Math.pow(targX, 2) + Math.pow(targY, 2));
        }


    }

    private class ClientThread extends Thread{
        String ip;
        int portThread;
        float altitude;
        float barometerAltitude;

        ClientThread(String ip, int portThread) {
            this.ip = ip;
            this.portThread = portThread;
        }

        public void run() {
            while(mFlightController==null){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            SocketClient ThreadClient1 = new SocketClient(this.ip,this.portThread);
            SocketClient ThreadClient2 = new SocketClient(this.ip,this.portThread+1);

            while (true) {
                mFlightControllerState = mFlightController.getState();
                barometerAltitude=mFlightControllerState.getAircraftLocation().getAltitude();
                altitude = mFlightControllerState.getUltrasonicHeightInMeters();
                byte[] byteArray1 = float2ByteArray(altitude);
                byte[] byteArray2 = float2ByteArray(barometerAltitude);

                ThreadClient1.execute(byteArray1);
                ThreadClient2.execute(byteArray2);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private byte[] float2ByteArray(float value) {
            return ByteBuffer.allocate(4).putFloat(value).array();
        }
    }

    private class SendImgThread extends Thread{
        String ip;
        int portThread;

        SendImgThread(String ip, int portThread) {
            this.ip = ip;
            this.portThread = portThread;
        }

        public void run() {
            SocketClient client = new SocketClient(ip,portThread);

            while(mFlightController==null){
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            while (true) {
                if(imagesList.size()>0 & videostreamPreviewTtView.getVisibility()!= View.VISIBLE) {

                    byte[] img= (byte[]) imagesList.getFirst();
                    client.execute(img);
                    imagesList.remove();
                    linkedListSize=linkedListSize-1;
                }
                else{
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private byte[] float2ByteArray(float value) {
            return ByteBuffer.allocate(4).putFloat(value).array();
        }
    }
}
