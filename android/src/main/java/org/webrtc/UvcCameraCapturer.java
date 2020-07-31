package org.webrtc;

import android.content.Context;
import android.util.Log;

public class UvcCameraCapturer extends CameraCapturer {
    private static final String TAG = UvcCameraCapturer.class.getSimpleName();

    private final Context context;

    public UvcCameraCapturer(Context context, String cameraName,
                             CameraEventsHandler eventsHandler, CameraEnumerator cameraEnumerator) {

        super(cameraName, eventsHandler, cameraEnumerator);

        this.context = context;

        // usb manager TODO

    }

    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
                                       CameraSession.Events events, Context applicationContext,
                                       SurfaceTextureHelper surfaceTextureHelper,
                                       String cameraName,
                                       int width, int height, int framerate) {
        Log.d(TAG, "createCameraSession");
        UvcCameraSession.create(createSessionCallback, events, applicationContext, surfaceTextureHelper,
            null, width, height, framerate);
    }
}
