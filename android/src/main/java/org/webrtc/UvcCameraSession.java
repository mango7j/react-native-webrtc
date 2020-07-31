package org.webrtc;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import com.oney.WebRTCModule.R;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.IButtonCallback;
import com.serenegiant.usb.IStatusCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class UvcCameraSession implements CameraSession {

    private static final String TAG = UvcCameraSession.class.getSimpleName();

    private static enum SessionState { RUNNING, STOPPED }

    private Handler cameraThreadHandler;
    private CreateSessionCallback callback;
    private Events events;
    private Context applicationContext;
    private SurfaceTextureHelper surfaceTextureHelper;
    private String cameraId;
    private int width;
    private int height;
    private int framerate;
    private CameraEnumerationAndroid.CaptureFormat captureFormat;
    private USBMonitor mUSBMonitor;
    private MediaRecorder mediaRecorder;
    private UVCCamera mUVCCamera;
    private final Object mSync = new Object();

    // Initialized at start

    // Initialized when camera opens
    private Surface mPreviewSurface;

    // Initialized when capture session is created

    // State
    private SessionState state;
    private boolean firstFrameReported = false;

    // Used only for stats. Only used on the camera thread.
    private long constructionTimeNs;    // Construction time of this class.

    private class DeviceConnectListener implements USBMonitor.OnDeviceConnectListener {
        @Override
        public void onAttach(UsbDevice device) {
            String prodName = device.getProductName();
            Logging.d(TAG, "OnDeviceConnectListener onAttach: " + prodName);
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            String prodName = device.getProductName();
            Logging.d(TAG, "OnDeviceConnectListener onConnect: " + prodName);

            try {
                releaseCamera();

                cameraThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Logging.d(TAG, "ctrlBlock: " + ctrlBlock.getProductName());
                        final UVCCamera camera = new UVCCamera();
                        camera.open(ctrlBlock);

                        camera.setStatusCallback(new IStatusCallback() {
                            @Override
                            public void onStatus(final int statusClass, final int event, final int selector,
                                                 final int statusAttribute, final ByteBuffer data) {
                                Logging.d("UsbDevice.onStatus", "onStatus(statusClass=" + statusClass
                                    + "; " +
                                    "event=" + event + "; " +
                                    "selector=" + selector + "; " +
                                    "statusAttribute=" + statusAttribute + "; " +
                                    "data=...)");
                            }
                        });
                        camera.setButtonCallback(new IButtonCallback() {
                            @Override
                            public void onButton(final int button, final int state) {
                                Logging.d("UsbDevice.onStatus", "onButton(button=" + button + "; " + "state=" + state + ")");
                            }
                        });

                        try {
                            camera.setPreviewSize(captureFormat.width, captureFormat.height, captureFormat.framerate.max);
                            Log.d(TAG, "onConnect] camera.setPreviewSize");
                        } catch (final IllegalArgumentException e) {
                            // fallback to YUV mode
                            try {
                                camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                            } catch (final IllegalArgumentException e1) {
                                camera.destroy();
                                return;
                            }
                        }
                        camera.setPreviewDisplay(mPreviewSurface);
                        camera.startPreview();

                        Log.d(TAG, "onConnect] camera.startPreview");

                        synchronized (mSync) {
                            mUVCCamera = camera;
                        }
                    }
                });
            }
            catch (Exception exp) {
                exp.printStackTrace();
            }
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            // XXX you should check whether the coming device equal to camera device that currently using
            //UvcCameraSession.this.events.onCameraError(UvcCameraSession.this, errorMessage);
            releaseCamera();
        }

        @Override
        public void onDettach(UsbDevice device) {
            //Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(UsbDevice device) {
            //UvcCameraSession.this.events.onFailure(FailureType.ERROR, "android.hardware.Camera.open returned null for camera id = " + cameraId);
        }
    }

    // ...

    public static void create(CreateSessionCallback callback, Events events, Context applicationContext,
                              SurfaceTextureHelper surfaceTextureHelper, MediaRecorder mediaRecorder,
                              int width, int height, int framerate) {
        new UvcCameraSession(callback, events, applicationContext, surfaceTextureHelper,
            mediaRecorder, width, height, framerate);
    }

    private UvcCameraSession(CreateSessionCallback callback, Events events, Context applicationContext,
                             SurfaceTextureHelper surfaceTextureHelper, @Nullable MediaRecorder mediaRecorder,
                             int width, int height, int framerate) {
        Logging.d(TAG, "Create new camera session on USB camera");

        constructionTimeNs = System.nanoTime();

        cameraThreadHandler = new Handler();
        this.callback = callback;
        this.events = events;
        this.applicationContext = applicationContext;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.mUSBMonitor = new USBMonitor(applicationContext, new DeviceConnectListener());
        // set filter TODO
        List<DeviceFilter> deviceFilters =
            DeviceFilter.getDeviceFilters(applicationContext, R.xml.device_filter);
        Log.d(TAG, "filters: " + deviceFilters);
        mUSBMonitor.setDeviceFilter(deviceFilters);

//        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.framerate = framerate;

        start();
    }

    private void start() {
        checkIsOnCameraThread();
        Logging.d(TAG, "start");

        events.onCameraOpening();
        findCaptureFormat();

        try {
            surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
            SurfaceTexture st = surfaceTextureHelper.getSurfaceTexture();
            if (st != null) {
                mPreviewSurface = new Surface(st);

                if (mediaRecorder != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        mediaRecorder.setInputSurface(mPreviewSurface);
                    }
                }
            }
            state = SessionState.RUNNING;
            listenForTextureFrames();

            mUSBMonitor.register();

            List<UsbDevice> devices = mUSBMonitor.getDeviceList();
            Logging.d(TAG, "start] devices: " + devices);

            if (devices.size() > 0) mUSBMonitor.requestPermission(devices.get(0));
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        callback.onDone(this);
    }

    private void findCaptureFormat() {
        // LATER: 개선 필요
        captureFormat = new CameraEnumerationAndroid.CaptureFormat(width, height, framerate, framerate);
    }

    private void listenForTextureFrames() {
        this.surfaceTextureHelper.startListening(new VideoSink() {
            @Override
            public void onFrame(VideoFrame frame) {
                checkIsOnCameraThread();
                if (state != SessionState.RUNNING) {
                    Logging.d(TAG, "Texture frame captured but camera is no longer running.");
//                    surfaceTextureHelper.returnTextureFrame(); TODO
                }
                else {
                    int rotation;
                    if (!firstFrameReported) {
                        rotation = (int) TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - UvcCameraSession.this.constructionTimeNs);
                        //UvcCameraSession.camera1StartTimeMsHistogram.addSample(rotation);
                        firstFrameReported = true;
                    }

                    rotation = getFrameOrientation();
                    //transformMatrix = RendererCommon.multiplyMatrices(transformMatrix, RendererCommon.horizontalFlipMatrix());

//                    VideoFrame.Buffer buffer = UvcCameraSession.this.surfaceTextureHelper.createTextureBuffer(UvcCameraSession.this.captureFormat.width, UvcCameraSession.this.captureFormat.height, RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix));
//                    VideoFrame frame = new VideoFrame(buffer, rotation, timestampNs);
                    events.onFrameCaptured(UvcCameraSession.this, frame);
//                    frame.release();
                }
            }
        });

    }

    @Override
    public void stop() {
        checkIsOnCameraThread();
        Logging.d(TAG, "Stop camera session on USB camera");
        finalize();
    }

    private boolean stopped = false;

    protected void finalize( )
    {
        if (stopped) return;

        if (state != SessionState.STOPPED) {
            stopInternal();
        }
        mUSBMonitor.unregister();
        mUSBMonitor.destroy();
        stopped = true;
    }

    private void stopInternal() {
        Logging.d(TAG, "Stop internal");
        if (state == SessionState.STOPPED) {
            Logging.d(TAG, "Camera is already stopped");
        } else {
            state = SessionState.STOPPED;
            surfaceTextureHelper.stopListening();
            releaseCamera();
            try {
                events.onCameraClosed(this);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            Logging.d(TAG, "Stop done");
        }

        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
    }

    private synchronized void releaseCamera() {
        // TODO synchronized 필요 여부 확인
        synchronized (mSync) {
            if (mUVCCamera != null) {
                UVCCamera temp = mUVCCamera;
                mUVCCamera = null;
                try {
                    temp.setStatusCallback(null);
                    temp.setButtonCallback(null);
                    temp.close();
                    temp.destroy();
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }

    private int getDeviceOrientation() {
        WindowManager wm = (WindowManager)applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int orientation = 0;
        switch (wm.getDefaultDisplay().getRotation()) {
            case 0:
            default:
                orientation = 0;
                break;
            case 1:
                orientation = 90;
                break;
            case 2:
                orientation = 180;
                break;
            case 3:
                orientation = 270;
        }

        return orientation;
    }

    private int getFrameOrientation() {
        int rotation = this.getDeviceOrientation();
        rotation = 360 - rotation;

        return rotation % 360;
    }

}
