/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2BasicFragment extends Fragment
        implements View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    // SparseIntArray是Android提供的一个数据结构，用来代替HashMap<Integer, Integer>，它的作用是当key为int类型时，可以提高查询效率
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    // REQUEST_CAMERA_PERMISSION 是请求相机权限的请求码
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    // FRAGMENT_DIALOG 是对话框的标识
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);    // 将屏幕旋转的角度和JPEG图片的角度对应起来
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";   // 日志的标识

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;  // 相机预览状态

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;    // 等待对焦状态

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;  // 等待曝光状态

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;  // 等待曝光状态

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;   // 拍照状态

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;  // 最大预览宽度

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080; // 最大预览高度

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a 处理多个生命周期事件
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener    // TextureView的监听器
            = new TextureView.SurfaceTextureListener() {

        @Override   // onSurfaceTextureAvailable的作用是 当TextureView可用时，打开相机
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override   // onSurfaceTextureSizeChanged的作用是 当TextureView的大小改变时，重新配置TextureView的变换矩阵
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override   // onSurfaceTextureDestroyed的作用是 当TextureView被销毁时，关闭相机
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override   // onSurfaceTextureUpdated的作用是 当TextureView更新时，进行相应的操作
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;   // 当前相机的ID

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;    // TextureView用于显示相机预览

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;   // CameraCaptureSession用于管理处理预览请求和拍照请求

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice; // CameraDevice代表系统摄像头，用于打开相机，关闭相机，捕获图片

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;  // 预览尺寸

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    // CameraDevice.StateCallback用于监听CameraDevice的状态
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override   // onOpened的作用是 当相机打开时，开启预览
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release(); // 释放锁
            mCameraDevice = cameraDevice;   // 获取CameraDevice实例
            createCameraPreviewSession();   // 创建CameraPreviewSession
        }

        @Override   // onDisconnected的作用是 当相机断开连接时，关闭相机
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release(); // 释放锁
            cameraDevice.close();   // 关闭相机
            mCameraDevice = null;   // 将CameraDevice置空
        }

        @Override   // onError的作用是 当相机发生错误时，关闭相机
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release(); // 释放锁
            cameraDevice.close();   // 关闭相机
            mCameraDevice = null;   // 将CameraDevice置空 CameraDevice是代表系统摄像头的一个类，用于打开相机，关闭相机，捕获图片
            Activity activity = getActivity();  // 获取Activity实例
            if (null != activity) { // 如果Activity不为空
                activity.finish();  // 结束Activity
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;    // 用于执行不应该阻塞UI的任务的额外线程

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler; // 用于在后台运行任务的Handler

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;   // ImageReader用于从CameraDevice获取图像数据

    /**
     * This is the output file for our picture.
     */
    private File mFile; // 保存图片的文件

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    // ImageReader的回调对象，当静止图像准备保存时，将调用“onImageAvailable”
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override   // onImageAvailable的作用是 当静止图像准备保存时，将调用“onImageAvailable”
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;  // 用于相机预览的CaptureRequest.Builder

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest; // 由mPreviewRequestBuilder生成的CaptureRequest CaptureRequest是用于描述捕获图片的请求的类

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW; // 当前相机状态

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);  // 信号量，防止在关闭相机之前退出应用程序

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;    // 当前相机设备是否支持闪光灯

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation; // 相机传感器的方向

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    // CameraCaptureSession.CaptureCallback用于处理与JPEG捕获相关的事件
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        //  CaptureCallback是CameraCaptureSession的一个抽象类，用于接收关于捕获进度更新的通知
        private void process(CaptureResult result) {    // process的作用是 处理捕获结果
            switch (mState) {   // 根据当前状态进行相应的操作
                case STATE_PREVIEW: {   // 如果当前状态是预览状态，就什么都不做
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {  // 如果当前状态是等待对焦状态
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);   // 获取对焦状态
                    if (afState == null) {  // 如果对焦状态为空，就拍照
                        captureStillPicture();  // 拍照
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||  // 如果对焦状态是对焦成功或者对焦锁定
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);   // 获取曝光状态
                        if (aeState == null ||  // 如果曝光状态为空或者曝光状态是曝光成功或者曝光锁定
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;   // 将当前状态置为拍照状态
                            captureStillPicture();  // 拍照
                        } else {
                            runPrecaptureSequence();    // 运行预捕获序列
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {    // 如果当前状态是等待曝光状态
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);   // 获取曝光状态
                    if (aeState == null ||  // 如果曝光状态为空或者曝光状态是曝光成功或者曝光锁定
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;  // 将当前状态置为等待非预捕获状态
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {    // 如果当前状态是等待非预捕获状态
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);   //  获取曝光状态
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {  // 如果曝光状态为空或者曝光状态不是预捕获状态
                        mState = STATE_PICTURE_TAKEN;   // 将当前状态置为拍照状态
                        captureStillPicture();  // 拍照
                    }
                    break;
                }
            }
        }

        @Override   // onCaptureProgressed的作用是 当部分图像捕获的结果可用时，将调用“onCaptureProgressed”
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult); // 处理捕获结果
        }

        @Override   // onCaptureCompleted的作用是 当图像捕获的结果可用时，将调用“onCaptureCompleted”
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);    // 处理捕获结果
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {  // 在UI线程上显示Toast
        final Activity activity = getActivity();    // 获取Activity实例
        if (activity != null) { // 如果Activity不为空
            activity.runOnUiThread(new Runnable() { // 在UI线程上运行
                @Override
                public void run() { // 运行
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();  // 显示Toast
                }
            });
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    // chooseOptimalSize的作用是 从相机支持的尺寸中选择最小的尺寸，该尺寸至少与相应的纹理视图尺寸一样大，
    // 并且最大尺寸与相应的最大尺寸一样大，并且其纵横比与指定的值匹配。
    // 如果不存在这样的大小，则选择最大的大小，最大的大小不超过相应的最大大小，并且其纵横比与指定的值匹配。
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();   // 用于保存大于等于预览Surface的支持的分辨率
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();    // 用于保存小于预览Surface的支持的分辨率
        int w = aspectRatio.getWidth(); // 获取宽度
        int h = aspectRatio.getHeight();    // 获取高度
        for (Size option : choices) {   // 遍历choices
            // 如果宽度小于等于最大宽度并且高度小于等于最大高度并且高度等于宽度乘以高度除以宽度
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                // 如果宽度大于等于纹理视图宽度并且高度大于等于纹理视图高度
                if (option.getWidth() >= textureViewWidth &&
                    option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);  // 将option添加到bigEnough中
                } else {
                    notBigEnough.add(option);   // 将option添加到notBigEnough中
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) { // 如果bigEnough的大小大于0
            return Collections.min(bigEnough, new CompareSizesByArea());    // 返回bigEnough中最小的Size
        } else if (notBigEnough.size() > 0) {   // 如果notBigEnough的大小大于0
            return Collections.max(notBigEnough, new CompareSizesByArea()); // 返回notBigEnough中最大的Size
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");  // 打印日志
            return choices[0];  // 返回choices中的第一个Size
        }
    }

    //  Camera2BasicFragment的构造方法   // 无参构造方法
    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override   // onCreate的作用是 当Fragment被创建时，调用onCreate()方法
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 返回布局文件
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override   // onViewCreated的作用是 当Fragment的视图被创建时，调用onViewCreated()方法
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);   // 设置拍照按钮的点击事件
        view.findViewById(R.id.info).setOnClickListener(this);  // 设置信息按钮的点击事件
        view.findViewById(R.id.toggle).setOnClickListener(this);    // 设置切换摄像头按钮的点击事件
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);    // 获取TextureView实例
    }

    @Override   // onActivityCreated的作用是 当Fragment所在的Activity被创建时，调用onActivityCreated()方法
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        //  获取保存图片的文件
        mFile = new File(getActivity().getExternalFilesDir(null), "pic.jpg");
    }

    @Override
    public void onResume() {    // onResume的作用是 当Fragment可见时，调用onResume()方法
        super.onResume();
        startBackgroundThread();    // 开启后台线程

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {   // 如果TextureView可用
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());  // 打开相机
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);    // 设置TextureView的监听器
        }
    }

    @Override   // onPause的作用是 当Fragment不可见时，调用onPause()方法
    public void onPause() {
        closeCamera();  // 关闭相机
        stopBackgroundThread(); // 停止后台线程
        super.onPause();    // 调用父类的onPause()方法
    }

    private void requestCameraPermission() {    // requestCameraPermission的作用是 请求相机权限
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) { // 如果需要显示请求权限的理由
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);  // 显示对话框
        } else {    // 如果不需要显示请求权限的理由
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);    // 请求相机权限
        }
    }

    @Override // onRequestPermissionsResult的作用是 当请求权限的结果返回时，调用onRequestPermissionsResult()方法
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {  // 如果请求码是请求相机权限的请求码
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {  // 如果授权结果的长度不为1或者授权结果不为授权
                ErrorDialog.newInstance(getString(R.string.request_permission))   // 显示对话框
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);   // 调用父类的onRequestPermissionsResult()方法
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings("SuspiciousNameCombination")  // 忽略警告
    private void setUpCameraOutputs(int width, int height) {    // setUpCameraOutputs的作用是 设置与相机相关的成员变量
        Activity activity = getActivity();  // 获取Activity实例
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);  // 获取CameraManager实例
        try {
            for (String cameraId : manager.getCameraIdList()) { // 遍历相机ID列表
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);   // 获取相机特性

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);    // 获取相机朝向
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {  // 如果相机朝向不为空并且相机朝向为前置相机
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(   // 获取相机支持的配置
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); // 获取相机支持的配置
                if (map == null) {  // 如果相机支持的配置为空
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max( // 获取最大的尺寸
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),    // 获取相机支持的JPEG格式的输出尺寸
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);  // 创建ImageReader实例
                mImageReader.setOnImageAvailableListener(   // 设置ImageReader的监听器
                        mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();    // 获取屏幕旋转的角度
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION); // 获取相机传感器的方向
                boolean swappedDimensions = false;  // 是否交换尺寸
                switch (displayRotation) {  // 根据屏幕旋转的角度进行相应的操作
                    case Surface.ROTATION_0:    // 如果屏幕旋转的角度为0
                    case Surface.ROTATION_180:  // 如果屏幕旋转的角度为180
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {    // 如果相机传感器的方向为90或者270
                            swappedDimensions = true;   // 将是否交换尺寸置为true
                        }
                        break;
                    case Surface.ROTATION_90:   // 如果屏幕旋转的角度为90
                    case Surface.ROTATION_270:  // 如果屏幕旋转的角度为270
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) { // 如果相机传感器的方向为0或者180
                            swappedDimensions = true;   // 将是否交换尺寸置为true
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);  // 打印日志
                }

                Point displaySize = new Point();    // 创建Point实例
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);   // 获取屏幕尺寸
                int rotatedPreviewWidth = width;    // 旋转预览宽度
                int rotatedPreviewHeight = height;  // 旋转预览高度
                int maxPreviewWidth = displaySize.x;    // 最大预览宽度
                int maxPreviewHeight = displaySize.y;   // 最大预览高度

                if (swappedDimensions) {    // 如果交换尺寸
                    rotatedPreviewWidth = height;   // 旋转预览宽度为高度
                    rotatedPreviewHeight = width;   // 旋转预览高度为宽度
                    maxPreviewWidth = displaySize.y;    // 最大预览宽度为屏幕高度
                    maxPreviewHeight = displaySize.x;   // 最大预览高度为屏幕宽度
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {  // 如果最大预览宽度大于最大预览宽度
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;    // 最大预览宽度为最大预览宽度
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {    // 如果最大预览高度大于最大预览高度
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;  // 最大预览高度为最大预览高度
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);   // 获取预览尺寸

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;    // 获取屏幕方向
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {   // 如果屏幕方向为横屏
                    mTextureView.setAspectRatio(    // 设置TextureView的宽高比
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {    // 如果屏幕方向为竖屏
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);    // 获取闪光灯是否可用
                mFlashSupported = available == null ? false : available;    // 将闪光灯是否可用置为available

                mCameraId = cameraId;   // 将相机ID置为cameraId
                return;
            }
        } catch (CameraAccessException e) { // 捕获Camera2BasicFragmentException异常 Camera2BasicFragment是自定义的异常
            e.printStackTrace();
        } catch (NullPointerException e) {  // 捕获NullPointerException异常 NullPointerException是空指针异常
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);  // 显示对话框
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {    // openCamera的作用是 打开相机
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)    // 如果没有相机权限
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();  // 请求相机权限
            return;
        }
        setUpCameraOutputs(width, height);  // 设置与相机相关的成员变量
        configureTransform(width, height);  // 配置变换
        Activity activity = getActivity();  // 获取Activity实例
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);  // 获取CameraManager实例
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {    // 如果没有获取到锁
                throw new RuntimeException("Time out waiting to lock camera opening."); // 抛出运行时异常
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);  // 打开相机
        } catch (CameraAccessException e) { // 捕获Camera2BasicFragmentException异常 Camera2BasicFragment是自定义的异常
            e.printStackTrace();
        } catch (InterruptedException e) {  // 捕获InterruptedException异常 InterruptedException是中断异常
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {    // closeCamera的作用是 关闭相机
        try {
            mCameraOpenCloseLock.acquire(); // 获取锁
            if (null != mCaptureSession) {  // 如果CameraCaptureSession不为空
                mCaptureSession.close();    // 关闭CameraCaptureSession
                mCaptureSession = null; // 将CameraCaptureSession置空
            }
            if (null != mCameraDevice) {    // 如果CameraDevice不为空
                mCameraDevice.close();  // 关闭CameraDevice
                mCameraDevice = null;   // 将CameraDevice置空
            }
            if (null != mImageReader) { // 如果ImageReader不为空
                mImageReader.close();   // 关闭ImageReader
                mImageReader = null;    // 将ImageReader置空
            }
        } catch (InterruptedException e) {  // 捕获InterruptedException异常 InterruptedException是中断异常
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release(); // 释放锁
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {  // startBackgroundThread的作用是 开启后台线程
        mBackgroundThread = new HandlerThread("CameraBackground");  // 创建HandlerThread实例
        mBackgroundThread.start();  // 开启后台线程
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());    // 创建Handler实例
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {   // stopBackgroundThread的作用是 停止后台线程
        mBackgroundThread.quitSafely(); // 安全地退出后台线程
        try {
            mBackgroundThread.join();   // 等待后台线程结束
            mBackgroundThread = null;   // 将后台线程置空
            mBackgroundHandler = null;  // 将Handler置空
        } catch (InterruptedException e) {  // 捕获InterruptedException异常 InterruptedException是中断异常
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() { // createCameraPreviewSession的作用是 创建新的CameraCaptureSession用于相机预览
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();  // 获取TextureView的SurfaceTexture实例
            assert texture != null; // 如果SurfaceTexture为空，就抛出断言异常

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());    // 设置SurfaceTexture的默认缓冲区大小

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture); // 创建Surface实例

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder  // 创建CaptureRequest.Builder实例
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);  // 将surface添加到CaptureRequest.Builder中

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),   // 创建CameraCaptureSession实例
                    new CameraCaptureSession.StateCallback() {  // 创建CameraCaptureSession的状态回调

                        @Override   // onConfigured的作用是 当CameraCaptureSession配置完成时，将调用“onConfigured”
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {    // 如果CameraDevice为空
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;  // 将CameraCaptureSession置为cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,  // 设置自动对焦模式
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // 设置自动对焦模式为连续自动对焦
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);   // 设置自动闪光灯

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();   // 创建CaptureRequest实例
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);  // 设置重复请求
                            } catch (CameraAccessException e) { // 捕获Camera2BasicFragmentException异常 Camera2BasicFragment是自定义的异常
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(  // onConfigureFailed的作用是 当CameraCaptureSession配置失败时，将调用“onConfigureFailed”
                                @NonNull CameraCaptureSession cameraCaptureSession) {   // CameraCaptureSession配置失败时，将调用“onConfigureFailed”
                            showToast("Failed");    // 显示Toast
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {    // configureTransform的作用是 配置变换
        Activity activity = getActivity();  // 获取Activity实例
        if (null == mTextureView || null == mPreviewSize || null == activity) {  // 如果TextureView或者预览尺寸或者Activity为空
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();   // 获取屏幕旋转的角度
        Matrix matrix = new Matrix();   // 创建Matrix实例
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);    // 创建RectF实例
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());  // 创建RectF实例
        float centerX = viewRect.centerX();     // 获取中心点的X坐标
        float centerY = viewRect.centerY();     // 获取中心点的Y坐标
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {  // 如果屏幕旋转的角度为90或者270
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());  // 将bufferRect的中心点移动到viewRect的中心点
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);   // 设置矩阵
            float scale = Math.max( // 获取最大的缩放比例
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);   // 设置缩放比例
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);   // 设置旋转角度
        } else if (Surface.ROTATION_180 == rotation) {  // 如果屏幕旋转的角度为180
            matrix.postRotate(180, centerX, centerY);   // 设置旋转角度
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        lockFocus();
    }   // takePicture的作用是 拍照

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {  // lockFocus的作用是 锁定焦点
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,   // 设置自动对焦触发
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;    // 将当前状态置为等待锁定状态
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,   // 设置重复请求
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {  // runPrecaptureSequence的作用是 运行预捕获序列
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,    // 设置自动曝光预捕获触发
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;  // 将当前状态置为等待预捕获状态
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);    // 设置重复请求
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {    // captureStillPicture的作用是 拍摄静止图片
        try {
            final Activity activity = getActivity();    // 获取Activity实例
            if (null == activity || null == mCameraDevice) {    // 如果Activity或者CameraDevice为空
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =   // 创建CaptureRequest.Builder实例
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());    // 将ImageReader的Surface添加到CaptureRequest.Builder中

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // 设置自动对焦模式为连续自动对焦
            setAutoFlash(captureBuilder);   // 设置自动闪光灯

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();   // 获取屏幕旋转的角度
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));  // 设置JPEG的方向

            CameraCaptureSession.CaptureCallback CaptureCallback        // 创建CameraCaptureSession.CaptureCallback实例
                    = new CameraCaptureSession.CaptureCallback() {

                @Override   // onCaptureCompleted的作用是 当图像捕获完毕时，将调用“onCaptureCompleted”
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    showToast("Saved: " + mFile);   // 显示Toast
                    Log.d(TAG, mFile.toString());   // 打印日志
                    unlockFocus();  // 解锁焦点
                }
            };

            mCaptureSession.stopRepeating();    // 停止重复请求
            mCaptureSession.abortCaptures();    // 中止捕获
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);  // 捕获静止图片
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {  // getOrientation的作用是 从指定的屏幕旋转中检索JPEG方向
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;   // 返回JPEG方向
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {    // unlockFocus的作用是 解锁焦点
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,   // 设置自动对焦触发
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);   // 设置自动闪光灯
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);    // 设置重复请求
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW; // 将当前状态置为预览状态
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);    // 设置重复请求
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View view) {    // onClick的作用是 当点击拍照按钮或者信息按钮时，调用onClick()方法
        switch (view.getId()) {
            case R.id.picture: {    // 如果点击的是拍照按钮
                takePicture();  // 拍照
                break;
            }
            case R.id.info: {   // 如果点击的是信息按钮
                Activity activity = getActivity();  // 获取Activity实例
                if (null != activity) { // 如果Activity不为空
                    new AlertDialog.Builder(activity)   // 创建AlertDialog.Builder实例
                            .setMessage(R.string.intro_message)  // 设置消息
                            .setPositiveButton(android.R.string.ok, null)   // 设置确定按钮
                            .show();    // 显示对话框
                }
                break;
            }
            case R.id.toggle: {    // 如果点击的是切换按钮
                takeToggle();  // 切换摄像头
                break;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void takeToggle() {
        //获取摄像头的管理者
        CameraManager cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = map.getOutputSizes(SurfaceTexture.class)[0]; //获取预览尺寸
                if (mCameraId.equals(String.valueOf(CameraCharacteristics.LENS_FACING_BACK)) && characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    mCameraId = String.valueOf(CameraCharacteristics.LENS_FACING_FRONT);
                    mCameraDevice.close();
                    backOrientation();
                    cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);  // 打开相机
                    break;
                } else if (mCameraId.equals(String.valueOf(CameraCharacteristics.LENS_FACING_FRONT)) && characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = String.valueOf(CameraCharacteristics.LENS_FACING_BACK);
                    mCameraDevice.close();
                    frontOrientation();
                    cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);  // 打开相机
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {  // setAutoFlash的作用是 设置自动闪光灯
        if (mFlashSupported) {      // 如果闪光灯可用
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,  // 设置自动曝光模式
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {   // ImageSaver的作用是 将JPEG图像保存到指定的文件中

        /**
         * The JPEG image
         */
        private final Image mImage; // JPEG图像
        /**
         * The file we save the image into.
         */
        private final File mFile;   // 保存图像的文件

        ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() { // run的作用是 将JPEG图像保存到指定的文件中
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();  // 获取ByteBuffer实例
            byte[] bytes = new byte[buffer.remaining()];    // 创建字节数组
            buffer.get(bytes);  // 将ByteBuffer中的数据复制到字节数组中
            FileOutputStream output = null;   // 创建FileOutputStream实例
            try {
                output = new FileOutputStream(mFile);   // 创建FileOutputStream实例
                output.write(bytes);    // 将字节数组中的数据写入到文件中
            } catch (IOException e) {   // 捕获IOException异常 IOException是输入输出异常
                e.printStackTrace();
            } finally { // 最终执行
                mImage.close(); // 关闭Image
                if (null != output) {   // 如果FileOutputStream不为空
                    try {
                        output.close(); // 关闭FileOutputStream
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {   // CompareSizesByArea的作用是 根据它们的面积比较两个Size

        @Override
        public int compare(Size lhs, Size rhs) {    // compare的作用是 根据它们的面积比较两个Size
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());   // 返回比较结果
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {    // ErrorDialog的作用是 显示错误消息对话框

        private static final String ARG_MESSAGE = "message";    // 消息

        public static ErrorDialog newInstance(String message) { // newInstance的作用是 创建ErrorDialog实例
            ErrorDialog dialog = new ErrorDialog(); // 创建ErrorDialog实例
            Bundle args = new Bundle(); // 创建Bundle实例
            args.putString(ARG_MESSAGE, message);   // 设置消息
            dialog.setArguments(args);  // 设置参数
            return dialog;  // 返回ErrorDialog实例
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {   // onCreateDialog的作用是 创建对话框
            final Activity activity = getActivity();    // 获取Activity实例
            return new AlertDialog.Builder(activity)    // 创建AlertDialog.Builder实例
                    .setMessage(getArguments().getString(ARG_MESSAGE))  // 设置消息
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { // 设置确定按钮
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {   // 当点击确定按钮时，调用onClick()方法
                            activity.finish();  // 结束Activity
                        }
                    })
                    .create();  // 创建对话框
        }

    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment { // ConfirmationDialog的作用是 显示关于相机权限的OK/Cancel确认对话框

        @NonNull
        @Override   // onCreateDialog的作用是 创建对话框
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();    // 获取父Fragment
            return new AlertDialog.Builder(getActivity())   // 创建AlertDialog.Builder实例
                    .setMessage(R.string.request_permission)    // 设置消息
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() { // 设置确定按钮
                        @Override
                        public void onClick(DialogInterface dialog, int which) {    // 当点击确定按钮时，调用onClick()方法
                            parent.requestPermissions(new String[]{Manifest.permission.CAMERA}, // 请求相机权限
                                    REQUEST_CAMERA_PERMISSION);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, //  设置取消按钮
                            new DialogInterface.OnClickListener() {   // 当点击取消按钮时，调用onClick()方法
                                @Override
                                public void onClick(DialogInterface dialog, int which) {    // 当点击确定按钮时，调用onClick()方法
                                    Activity activity = parent.getActivity();   // 获取Activity实例
                                    if (activity != null) { // 如果Activity不为空
                                        activity.finish();  // 结束Activity
                                    }
                                }
                            })
                    .create();  // 创建对话框
        }
    }

    //前置拍摄时，照片旋转270
    private void frontOrientation() {
        //前置时，照片旋转270
        ORIENTATIONS.append(Surface.ROTATION_0, 270);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 90);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //后置拍摄时，照片旋转90
    private static void backOrientation() {
        //后置时，照片旋转90
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    
}
