package org.webrtc;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.UvcCameraCapturer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UvcCamera2Enumerator extends Camera2Enumerator {
    private final static String TAG = UvcCamera2Enumerator.class.getSimpleName();
    private final static String USB_CAM_NAME = "UsbCamName";

    private Context context;
    private UsbManager usbManager;

    public UvcCamera2Enumerator(Context context) {
        super(context);
        this.context = context;
        usbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public String[] getDeviceNames() {
        if (usbManager.getDeviceList().size() > 0) {
            String keys = TextUtils.join(",", usbManager.getDeviceList().keySet());
            Log.d(TAG, "getDeviceNames] deviceList: " + keys);
            return new String[] { USB_CAM_NAME };
        }
        else {
            ArrayList<String> namesList = new ArrayList<>(Arrays.asList(super.getDeviceNames()));
            // check a USB camera connected.
            /*if (usbManager.getDeviceList().size() > 0) {
                namesList.add(0, USB_CAM_NAME);
            }*/
            String[] names = new String[namesList.size()];
            return namesList.toArray(names);
        }

//        Log.d(TAG, TextUtils.join(",", namesList));
    }

    @Override
    public boolean isFrontFacing(String deviceName) {
        if (USB_CAM_NAME.equals(deviceName)) return false;
        return super.isFrontFacing(deviceName);
    }

    @Override
    public boolean isBackFacing(String deviceName) {
        if (USB_CAM_NAME.equals(deviceName)) return true;  // TODO false | true 확인
        return super.isFrontFacing(deviceName);
    }

    @Override
    public List<CameraEnumerationAndroid.CaptureFormat> getSupportedFormats(String deviceName) {
        // TODO
        if (USB_CAM_NAME.equals(deviceName)) return new ArrayList<>();
        return super.getSupportedFormats(deviceName);
    }

    @Override
    public CameraVideoCapturer createCapturer(
        String deviceName, CameraVideoCapturer.CameraEventsHandler eventsHandler) {
        Log.d(TAG, "createCapturer] deviceName: " + deviceName);
        if (USB_CAM_NAME.equals(deviceName)) {
            return new UvcCameraCapturer(context, deviceName, eventsHandler, this);
        } else {
            return super.createCapturer(deviceName, eventsHandler);
        }
    }
}