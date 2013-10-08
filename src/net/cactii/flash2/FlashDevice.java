package net.cactii.flash2;

import java.io.FileWriter;
import java.io.IOException;

import javax.microedition.khronos.opengles.GL10;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.content.Context;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

public class FlashDevice {
    private static final String TAG = "TorchDevice";

    private static int mValueOff;
    private static int mValueOn;
    private static int mValueLow;
    private static int mValueHigh;
    private static String mFlashDevice;
    private static String mFlashDeviceLuminosity;
    private static boolean mUseCameraInterface;
    private WakeLock mWakeLock;

    public static final int STROBE    = -1;
    public static final int OFF       = 0;
    public static final int ON        = 1;

    private static FlashDevice mInstance;
    private static boolean mSurfaceCreated = false;
    private static SurfaceTexture mSurfaceTexture;

    private FileWriter mFlashDeviceWriter = null;
    private FileWriter mFlashDeviceLuminosityWriter = null;

    private int mFlashMode = OFF;

    private Camera mCamera = null;
    private Camera.Parameters mParams;

    private FlashDevice(Context context) {
        mValueOff = context.getResources().getInteger(R.integer.valueOff);
        mValueOn = context.getResources().getInteger(R.integer.valueOn);
        mValueLow = context.getResources().getInteger(R.integer.valueLow);
        mValueHigh = context.getResources().getInteger(R.integer.valueHigh);
        mFlashDevice = context.getResources().getString(R.string.flashDevice);
        mFlashDeviceLuminosity = context.getResources().getString(R.string.flashDeviceLuminosity);
        mUseCameraInterface = context.getResources().getBoolean(R.bool.useCameraInterface);
        if (mUseCameraInterface) {
            PowerManager pm
                = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            this.mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Torch");
        }
    }

    public static synchronized FlashDevice getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new FlashDevice(context);
        }
        return mInstance;
    }

    public synchronized void setFlashMode(int mode, boolean bright) {
        if (mode == mFlashMode) {
            return;
        }
        
        try {
            int brightnessValue = 0;

            switch (mode) {
                case ON:
                    brightnessValue = mValueOn;
                    if (bright) {
                        if (mValueHigh != -1){
                            brightnessValue = mValueHigh;
                        }
                    } else {
                        if (mValueLow != -1){
                            brightnessValue = mValueLow;
                        }
                    }
                    break;
                case OFF:
                    brightnessValue = mValueOff;
                    break;
            }
            if (mUseCameraInterface) {
                if (mCamera == null) {
                    mCamera = Camera.open();
                }
                if (mode == OFF || mode == STROBE) {
                    mParams = mCamera.getParameters();
                    mParams.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    mCamera.setParameters(mParams);
                    if (mode != STROBE) {
                        mCamera.stopPreview();
                        mCamera.release();
                        mCamera = null;
                        mSurfaceCreated = false;
                    }
                    if (mWakeLock.isHeld())
                        mWakeLock.release();
                } else {
                    if (!mSurfaceCreated) {
                        int[] textures = new int[1];
                        // generate one texture pointer and bind it as an
                        // external texture.
                        GLES20.glGenTextures(1, textures, 0);
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                textures[0]);
                        // No mip-mapping with camera source.
                        GLES20.glTexParameterf(
                                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
                        GLES20.glTexParameterf(
                                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
                        // Clamp to edge is only option.
                        GLES20.glTexParameteri(
                                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
                        GLES20.glTexParameteri(
                                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

                        FlashDevice.mSurfaceTexture = new SurfaceTexture(textures[0]);
                        mCamera.setPreviewTexture(FlashDevice.mSurfaceTexture);
                        mSurfaceCreated = true;
                        mCamera.startPreview();
                    }
                    mParams = mCamera.getParameters();
                    mParams.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                    mCamera.setParameters(mParams);
                    if (!mWakeLock.isHeld()) {  // only get the wakelock if we don't have it already
                        mWakeLock.acquire(); // we don't want to go to sleep while cam is up
                    }
                }
            } else {
                // Devices with sysfs toggle and sysfs luminosity
                if (mFlashDeviceLuminosity != null && mFlashDeviceLuminosity.length() > 0) {
                    if (mFlashDeviceWriter == null) {
                        mFlashDeviceWriter = new FileWriter(mFlashDevice);
                    }
                    if (mFlashDeviceLuminosityWriter == null) {
                        mFlashDeviceLuminosityWriter = new FileWriter(mFlashDeviceLuminosity);
                    }

                    switch (mode) {
                        case ON:
                            mFlashDeviceWriter.write(String.valueOf(mValueOn));
                            mFlashDeviceWriter.flush();
                            mFlashDeviceLuminosityWriter.write(String.valueOf(brightnessValue));
                            mFlashDeviceLuminosityWriter.flush();
                            break;
                        case STROBE:
                            mFlashDeviceWriter.write(String.valueOf(mValueOff));
                            mFlashDeviceWriter.flush();
                            break;
                        case OFF:
                            mFlashDeviceWriter.write(String.valueOf(mValueOff));
                            mFlashDeviceWriter.flush();
                            mFlashDeviceWriter.close();
                            mFlashDeviceLuminosityWriter.write(String.valueOf(brightnessValue));
                            mFlashDeviceLuminosityWriter.flush();
                            mFlashDeviceLuminosityWriter.close();
                            mFlashDeviceLuminosityWriter = null;
                            mFlashDeviceWriter = null;
                            break;
                    }
                } else {
                    // Devices with just a sysfs toggle
                    if (mFlashDeviceWriter == null) {
                        mFlashDeviceWriter = new FileWriter(mFlashDevice);
                    }

                    switch (mode) {
                        case ON:
                            mFlashDeviceWriter.write(String.valueOf(brightnessValue));
                            mFlashDeviceWriter.flush();
                            break;
                        case STROBE:
                            mFlashDeviceWriter.write(String.valueOf(mValueOff));
                            mFlashDeviceWriter.flush();
                            break;
                        case OFF:
                            mFlashDeviceWriter.write(String.valueOf(brightnessValue));
                            mFlashDeviceWriter.flush();
                            mFlashDeviceWriter.close();
                            mFlashDeviceWriter = null;
                            break;
                    }
                }
            }
            mFlashMode = mode;
        } catch (IOException e) {
            throw new RuntimeException("Can't open flash device", e);
        }
    }

    public int getFlashMode() {
        return mFlashMode;
    }
}
