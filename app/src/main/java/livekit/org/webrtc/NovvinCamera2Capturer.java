package livekit.org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;

/** Uses the SDK lifecycle with an advertised steady camera cadence. */
public final class NovvinCamera2Capturer extends Camera2Capturer {
    public NovvinCamera2Capturer(Context context, String cameraId, CameraEventsHandler events) {
        super(context, cameraId, events);
    }

    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback callback,
            CameraSession.Events events, Context context, SurfaceTextureHelper helper,
            String cameraId, int width, int height, int fps) {
        NovvinCamera2Session.create(callback, events, context,
            (CameraManager) context.getSystemService(Context.CAMERA_SERVICE), helper,
            cameraId, width, height, fps);
    }
}
