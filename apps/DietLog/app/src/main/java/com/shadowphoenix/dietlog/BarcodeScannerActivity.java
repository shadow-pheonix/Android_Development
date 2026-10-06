package com.shadowphoenix.dietlog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BarcodeScannerActivity extends Activity implements TextureView.SurfaceTextureListener {
    public static final String EXTRA_BARCODE = "barcode";
    public static final String EXTRA_FORMAT = "barcode_format";

    private static final int CAMERA_PERMISSION_REQUEST = 70;
    private static final String STATE_PERMISSION_REQUESTED = "permission_requested";
    private static final long FRAME_INTERVAL_NS = 180_000_000L;

    private final Object cameraLock = new Object();
    private final AtomicBoolean decodeInFlight = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final MultiFormatReader barcodeReader = new MultiFormatReader();

    private TextureView preview;
    private TextView statusText;
    private TextView retryButton;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size previewSize;
    private boolean continuousAfSupported;
    private boolean aeOnSupported;
    private boolean awbAutoSupported;
    private boolean cameraOpening;
    private boolean resumed;
    private boolean permissionRequested;
    private long lastFrameAcceptedNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this);
        CrashHandler.install(this);
        permissionRequested = savedInstanceState != null && savedInstanceState.getBoolean(STATE_PERMISSION_REQUESTED, false);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        setContentView(R.layout.screen_barcode_scanner);

        preview = findViewById(R.id.scannerPreview);
        statusText = findViewById(R.id.scannerStatus);
        retryButton = findViewById(R.id.scannerRetry);
        preview.setSurfaceTextureListener(this);
        findViewById(R.id.scannerClose).setOnClickListener(v -> finish());
        retryButton.setOnClickListener(v -> retryCameraPermissionOrSettings());

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = Arrays.asList(
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        barcodeReader.setHints(hints);

        AppLogger.i("Barcode scanner opened");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startCameraThread();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hideRetry();
            if (preview.isAvailable()) openCamera();
        } else if (!permissionRequested) {
            requestCameraPermission();
        } else {
            showPermissionDenied();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequested);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        resumed = false;
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        decodeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void requestCameraPermission() {
        permissionRequested = true;
        statusText.setText("Camera access is required to scan barcodes");
        retryButton.setVisibility(View.GONE);
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        AppLogger.i("Barcode camera permission " + (granted ? "granted" : "denied"));
        if (granted) {
            hideRetry();
            if (resumed && preview.isAvailable()) openCamera();
        } else {
            showPermissionDenied();
        }
    }

    private void showPermissionDenied() {
        retryButton.setOnClickListener(v -> retryCameraPermissionOrSettings());
        boolean canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        statusText.setText(canAskAgain
                ? "Camera permission denied. FuelNote only uses it while this scanner is open."
                : "Camera permission is off. Enable it in FuelNote app settings to scan barcodes.");
        retryButton.setText(canAskAgain ? "Try camera permission again" : "Open app settings");
        retryButton.setVisibility(View.VISIBLE);
    }

    private void retryCameraPermissionOrSettings() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hideRetry();
            if (resumed && preview.isAvailable()) openCamera();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestCameraPermission();
        } else {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void hideRetry() {
        retryButton.setVisibility(View.GONE);
        statusText.setText("Point the camera at the barcode");
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("DietLogBarcodeCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread == null) return;
        thread.quitSafely();
        try {
            thread.join(750);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        if (!resumed || finished.get() || !preview.isAvailable() ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        synchronized (cameraLock) {
            if (cameraDevice != null || cameraOpening || cameraHandler == null) return;
            cameraOpening = true;
        }

        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = chooseCameraId(manager);
            if (cameraId == null) {
                synchronized (cameraLock) { cameraOpening = false; }
                showCameraError("No usable camera was found on this device.");
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            continuousAfSupported = contains(
                    characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            aeOnSupported = contains(
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AE_MODE_ON);
            awbAutoSupported = contains(
                    characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AWB_MODE_AUTO);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("No stream configuration");

            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class), 1920 * 1080);
            Size scanSize = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), 1280 * 960);
            if (previewSize == null || scanSize == null) throw new IllegalStateException("No supported preview size");

            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) throw new IllegalStateException("Preview surface unavailable");
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configureTransform(preview.getWidth(), preview.getHeight());

            synchronized (cameraLock) {
                if (imageReader != null) imageReader.close();
                imageReader = ImageReader.newInstance(scanSize.getWidth(), scanSize.getHeight(), ImageFormat.YUV_420_888, 3);
                imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            }
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            synchronized (cameraLock) { cameraOpening = false; }
            AppLogger.e("Barcode camera failed to open", e);
            showCameraError("Unable to start the camera. Close the scanner and try again.");
        }
    }

    private String chooseCameraId(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            if (fallback == null) fallback = id;
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    private static boolean contains(int[] values, int expected) {
        if (values == null) return false;
        for (int value : values) if (value == expected) return true;
        return false;
    }

    private static Size chooseSize(Size[] sizes, long maxArea) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        Size smallest = sizes[0];
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            long smallestArea = (long) smallest.getWidth() * smallest.getHeight();
            if (area < smallestArea) smallest = size;
            if (area <= maxArea && (best == null || area > (long) best.getWidth() * best.getHeight())) best = size;
        }
        return best != null ? best : smallest;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            synchronized (cameraLock) {
                cameraOpening = false;
                if (!resumed || finished.get() ||
                        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    camera.close();
                    return;
                }
                cameraDevice = camera;
            }
            createCaptureSession();
        }

        @Override public void onDisconnected(CameraDevice camera) {
            AppLogger.i("Barcode camera disconnected");
            camera.close();
            synchronized (cameraLock) {
                cameraOpening = false;
                if (cameraDevice == camera) cameraDevice = null;
            }
        }

        @Override public void onError(CameraDevice camera, int error) {
            AppLogger.i("Barcode camera device error=" + error);
            camera.close();
            synchronized (cameraLock) {
                cameraOpening = false;
                if (cameraDevice == camera) cameraDevice = null;
            }
            showCameraError("Camera error. Close the scanner and try again.");
        }
    };

    private void createCaptureSession() {
        final CameraDevice camera;
        final ImageReader reader;
        synchronized (cameraLock) {
            camera = cameraDevice;
            reader = imageReader;
        }
        if (camera == null || reader == null || !preview.isAvailable()) return;

        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            synchronized (cameraLock) {
                if (previewSurface != null) previewSurface.release();
                previewSurface = surface;
            }
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            request.addTarget(reader.getSurface());
            if (continuousAfSupported) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            if (aeOnSupported) request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (awbAutoSupported) request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    synchronized (cameraLock) {
                        if (cameraDevice == null) {
                            session.close();
                            return;
                        }
                        captureSession = session;
                    }
                    try {
                        session.setRepeatingRequest(request.build(), null, cameraHandler);
                        runOnUiThread(() -> {
                            hideRetry();
                            statusText.setText("Scanning… hold the barcode inside the teal frame");
                        });
                        AppLogger.i("Barcode camera session ready");
                    } catch (CameraAccessException | IllegalStateException e) {
                        AppLogger.e("Barcode camera repeating request failed", e);
                        showCameraError("Unable to start barcode scanning.");
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    AppLogger.i("Barcode camera session configuration failed");
                    showCameraError("Unable to configure the camera for barcode scanning.");
                }
            }, cameraHandler);
        } catch (Exception e) {
            AppLogger.e("Barcode camera session failed", e);
            showCameraError("Unable to start barcode scanning.");
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || finished.get()) return;
            long now = System.nanoTime();
            if (now - lastFrameAcceptedNs < FRAME_INTERVAL_NS || !decodeInFlight.compareAndSet(false, true)) return;
            lastFrameAcceptedNs = now;

            int width = image.getWidth();
            int height = image.getHeight();
            byte[] luma = copyLuma(image);
            try {
                decodeExecutor.execute(() -> decodeFrame(luma, width, height));
            } catch (RejectedExecutionException e) {
                decodeInFlight.set(false);
            }
        } catch (Exception e) {
            decodeInFlight.set(false);
            AppLogger.e("Barcode frame processing failed", e);
        } finally {
            if (image != null) image.close();
        }
    }

    private void decodeFrame(byte[] luma, int width, int height) {
        try {
            Result result = tryDecode(luma, width, height);
            if (result == null) {
                byte[] rotated = rotate90(luma, width, height);
                result = tryDecode(rotated, height, width);
            }
            if (result != null && isSupported(result.getBarcodeFormat())) {
                deliverResult(result.getText(), result.getBarcodeFormat());
            }
        } finally {
            decodeInFlight.set(false);
        }
    }

    private Result tryDecode(byte[] luma, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    luma, width, height, 0, 0, width, height, false);
            return barcodeReader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
        } catch (ReaderException ignored) {
            return null;
        } finally {
            barcodeReader.reset();
        }
    }

    private static boolean isSupported(BarcodeFormat format) {
        return format == BarcodeFormat.EAN_13 || format == BarcodeFormat.EAN_8 ||
                format == BarcodeFormat.UPC_A || format == BarcodeFormat.UPC_E;
    }

    private void deliverResult(String value, BarcodeFormat format) {
        if (value == null || !value.matches("\\d{6,14}") || !finished.compareAndSet(false, true)) return;
        AppLogger.i("Barcode scan success format=" + format + " digits=" + value.length());
        runOnUiThread(() -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_BARCODE, value);
            result.putExtra(EXTRA_FORMAT, format.name());
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private static byte[] copyLuma(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] out = new byte[width * height];
        int limit = buffer.limit();
        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            int outputOffset = y * width;
            for (int x = 0; x < width; x++) {
                int index = rowOffset + x * pixelStride;
                if (index >= limit) break;
                out[outputOffset + x] = buffer.get(index);
            }
        }
        return out;
    }

    private static byte[] rotate90(byte[] input, int width, int height) {
        byte[] out = new byte[input.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[x * height + (height - y - 1)] = input[y * width + x];
            }
        }
        return out;
    }

    private void showCameraError(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            retryButton.setText("Retry camera");
            retryButton.setVisibility(View.VISIBLE);
            retryButton.setOnClickListener(v -> {
                retryButton.setVisibility(View.GONE);
                closeCamera();
                openCamera();
            });
        });
    }

    private void closeCamera() {
        synchronized (cameraLock) {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
            cameraOpening = false;
        }
        decodeInFlight.set(false);
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90f * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY);
        }
        preview.setTransform(matrix);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (resumed && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        configureTransform(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
}
