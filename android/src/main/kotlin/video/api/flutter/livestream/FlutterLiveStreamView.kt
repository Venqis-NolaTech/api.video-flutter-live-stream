package video.api.flutter.livestream

import android.Manifest
import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import io.flutter.view.TextureRegistry
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.IPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.backCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.externalCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.frontCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isBackCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isExternalCamera
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrontCamera
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startPreview
import io.github.thibaultbee.streampack.core.interfaces.stopPreview
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.ext.rtmp.configuration.mediadescriptor.RtmpMediaDescriptor
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpointFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class FlutterLiveStreamView(
    private val context: Context,
    private val textureRegistry: TextureRegistry,
    private val permissionsManager: PermissionsManager,
    private val onConnectionSucceeded: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConnectionFailed: (String) -> Unit,
    private val onGenericError: (Exception) -> Unit,
    private val onVideoSizeChanged: (Size) -> Unit,
    private val onTextureRecreated: (Long) -> Unit,
) {
    companion object {
        private const val TAG = "FlutterLiveStreamView"
    }

    private var flutterTexture = textureRegistry.createSurfaceTexture()
    val textureId: Long
        get() = flutterTexture.id()

    /**
     * Creates a new SurfaceTexture for camera switch - forces Flutter to render new camera frames.
     * Must run on the Android main thread — [TextureRegistry] entry release/register are @UiThread.
     */
    private fun recreateFlutterTextureOnMainThread() {
        Log.d(TAG, "recreateFlutterTexture | oldTextureId=${flutterTexture.id()}")
        flutterTexture.release()
        flutterTexture = textureRegistry.createSurfaceTexture()
        Log.d(TAG, "recreateFlutterTexture | newTextureId=${textureId}")
        onTextureRecreated(textureId)
    }

    /** Created in [init] after [StreamPackAndroidLoggerInstaller] so StreamPack logs are visible. */
    private lateinit var streamer: SingleStreamer

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Default)

    /** Serializes preview attach/detach vs camera switch — avoids orphaned Surfaces racing Camera2. */
    private val previewMutex = Mutex()

    /** Last preview [Surface]; must [Surface.release] before allocating another for the same [flutterTexture]. */
    private var previewSurface: Surface? = null

    private var _isPreviewing = false
    private var _isStreaming = false
    val isStreaming: Boolean
        get() = _isStreaming

    private var _videoConfig: VideoCodecConfig? = null
    val videoConfig: VideoCodecConfig
        get() = _videoConfig!!

    private val cameraId: String
        get() = (streamer.videoInput?.sourceFlow?.value as? ICameraSource)?.cameraId
            ?: throw IllegalStateException("Camera source is not ready")

    init {
        StreamPackAndroidLoggerInstaller.installOnce()
        streamer = runBlocking(Dispatchers.Default) {
            cameraSingleStreamer(
                context,
                endpointFactory = RtmpEndpointFactory(),
            )
        }
        Log.d(TAG, "cameraSingleStreamer created | textureId=$textureId")

        scope.launch {
            streamer.throwableFlow
                .filterNotNull()
                .collect { t ->
                    _isStreaming = false
                    Log.e(TAG, "streamer throwableFlow", t)
                    val ex = t as? Exception ?: Exception(t.message, t)
                    onGenericError(ex)
                }
        }
        scope.launch {
            streamer.isOpenFlow
                .filter { !it }
                .collect {
                    if (_isStreaming) {
                        _isStreaming = false
                        Log.d(TAG, "isOpenFlow became false -> onDisconnected")
                        onDisconnected()
                    }
                }
        }
    }

    fun setVideoConfig(
        videoConfig: VideoCodecConfig,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.d(TAG, "setVideoConfig | resolution=${videoConfig.resolution} fps=${videoConfig.fps}")

        if (_isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        onVideoSizeChanged(videoConfig.resolution)

        val wasPreviewing = _isPreviewing
        if (wasPreviewing) {
            stopPreview()
        }
        try {
            runBlocking(Dispatchers.Default) {
                streamer.setVideoConfig(videoConfig)
                // Ensure pipeline applied config before preview (avoid race with encoder/preview setup).
                withTimeout(15_000) {
                    streamer.videoConfigFlow.first { it != null && it == videoConfig }
                }
            }
            _videoConfig = videoConfig
            Log.d(TAG, "setVideoConfig success | wasPreviewing=$wasPreviewing")
            if (wasPreviewing) {
                startPreview(onSuccess, onError)
            } else {
                onSuccess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setVideoConfig failed", e)
            onError(e)
        }
    }

    private var _audioConfig: AudioCodecConfig? = null
    val audioConfig: AudioCodecConfig
        get() = _audioConfig!!

    fun setAudioConfig(
        audioConfig: AudioCodecConfig,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.d(TAG, "setAudioConfig | bitrate=${audioConfig.startBitrate}")

        if (_isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        permissionsManager.requestPermission(
            Manifest.permission.RECORD_AUDIO,
            onGranted = {
                try {
                    runBlocking(Dispatchers.Default) {
                        streamer.setAudioConfig(audioConfig)
                    }
                    _audioConfig = audioConfig
                    Log.d(TAG, "setAudioConfig success")
                    onSuccess()
                } catch (e: Exception) {
                    Log.e(TAG, "setAudioConfig failed", e)
                    onError(e)
                }
            },
            onRationaleProceed = { _: () -> Unit ->
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.RECORD_AUDIO"))
            })
    }

    var isMuted: Boolean
        get() = streamer.audioInput?.isMuted ?: false
        set(value) {
            streamer.audioInput?.isMuted = value
        }

    val camera: String
        get() = cameraId

    fun setCamera(camera: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        Log.d(TAG, "setCamera | cameraId=$camera")

        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                // Run off the UI thread (permission callback may be Main); avoid runBlocking there.
                scope.launch(Dispatchers.Default) {
                    try {
                        restartCameraIfPreviewWasActive(camera, onSuccess, onError)
                    } catch (e: Exception) {
                        Log.e(TAG, "setCamera failed", e)
                        onError(e)
                    }
                }
            },
            onRationaleProceed = { _: () -> Unit ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    /**
     * StreamPack [VideoInput.setSource] already tears down the previous [CameraSource] on camera↔camera
     * swap (stopStream + release). Calling [streamer.stopPreview] *before* [setCameraId] fights that
     * lifecycle and can leave preview stuck (frames never resume on the Flutter texture).
     *
     * After [setCameraId], we must drop our [Surface] wrapper and run [startPreviewPipelineLocked] so the
     * new camera binds the Flutter [SurfaceTexture] again.
     *
     * @see io.github.thibaultbee.streampack.core.pipelines.inputs.VideoInput.setSource
     */
    private suspend fun restartCameraIfPreviewWasActive(
        cameraId: String,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        var failed: Exception? = null
        previewMutex.withLock {
            val wasPreviewing = _isPreviewing
            Log.d(
                TAG,
                "restartCameraIfPreviewWasActive | wasPreviewing=$wasPreviewing | cameraId=$cameraId " +
                    "(no streamer.stopPreview before setCameraId — StreamPack owns camera swap teardown)",
            )

            try {
                streamer.setCameraId(cameraId)
            } catch (e: Exception) {
                Log.e(TAG, "restartCameraIfPreviewWasActive | setCameraId failed", e)
                failed = e
                return@withLock
            }
            Log.d(TAG, "restartCameraIfPreviewWasActive | setCameraId success")

            if (wasPreviewing) {
                // TextureRegistry.release/createSurfaceTexture must run on main (Flutter JNI).
                withContext(Dispatchers.Main) {
                    recreateFlutterTextureOnMainThread()
                }

                // Native preview was torn down with the old CameraSource even if StreamPack raced our flag.
                releasePreviewSurfaceLocked()
                _isPreviewing = false

                val cfg = _videoConfig
                if (cfg == null) {
                    failed = IllegalStateException("Video has not been configured!")
                    return@withLock
                }
                try {
                    startPreviewPipelineLocked(cfg)
                    Log.d(TAG, "restartCameraIfPreviewWasActive | preview restarted with new texture")
                } catch (e: Exception) {
                    Log.e(TAG, "restartCameraIfPreviewWasActive | startPreview failed", e)
                    failed = e
                }
            }
        }
        if (failed != null) {
            onError(failed!!)
        } else {
            onSuccess()
        }
    }

    private fun releasePreviewSurfaceLocked() {
        previewSurface?.release()
        previewSurface = null
    }

    /**
     * Creates a new [Surface] for [flutterTexture], releasing any previous one.
     * Caller must hold [previewMutex] (or run single-threaded before first preview).
     */
    private fun createPreviewSurfaceLocked(resolution: Size): Surface {
        releasePreviewSurfaceLocked()
        val previewSize = (streamer.videoInput?.sourceFlow?.value as? IPreviewableSource)
            ?.getPreviewSize(resolution, SurfaceTexture::class.java)
            ?: resolution
        Log.d(
            TAG,
            "createPreviewSurfaceLocked | requested=$resolution previewSize=$previewSize",
        )
        val surfaceTexture = flutterTexture.surfaceTexture().apply {
            setDefaultBufferSize(
                previewSize.width,
                previewSize.height,
            )
        }
        return Surface(surfaceTexture).also { previewSurface = it }
    }

    private suspend fun stopPreviewPipelineLocked() {
        try {
            streamer.stopPreview()
        } catch (_: Exception) {
        }
        releasePreviewSurfaceLocked()
        _isPreviewing = false
    }

    /** Start preview; caller must hold [previewMutex]. */
    private suspend fun startPreviewPipelineLocked(config: VideoCodecConfig) {
        val surface = createPreviewSurfaceLocked(config.resolution)
        streamer.startPreview(surface)
        _isPreviewing = true
        Log.d(TAG, "startPreviewPipelineLocked done")
    }

    /** Same surface binding as [startPreview] inner path (permission already verified). */
    private suspend fun startPreviewSurfaceSuspended() {
        previewMutex.withLock {
            val cfg = checkNotNull(_videoConfig) { "Video has not been configured!" }
            // Defensive: duplicate Dart [startPreview] after [initialize] would stack Surfaces.
            if (_isPreviewing) {
                stopPreviewPipelineLocked()
            }
            startPreviewPipelineLocked(cfg)
        }
        Log.d(TAG, "startPreviewSurfaceSuspended done")
    }

    val cameraPosition: String
        get() = when {
            context.cameraManager.isFrontCamera(cameraId) -> "front"
            context.cameraManager.isBackCamera(cameraId) -> "back"
            context.cameraManager.isExternalCamera(cameraId) -> "other"
            else -> throw IllegalArgumentException("Invalid camera position for camera $cameraId")
        }

    fun setCameraPosition(position: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        Log.d(TAG, "setCameraPosition | position=$position")

        val cameraList = when (position) {
            "front" -> context.cameraManager.frontCameras
            "back" -> context.cameraManager.backCameras
            "other" -> context.cameraManager.externalCameras
            else -> throw IllegalArgumentException("Invalid camera position: $position")
        }
        setCamera(cameraList.first(), onSuccess, onError)
    }

    fun dispose() {
        Log.d(TAG, "dispose")

        stopStream()
        runBlocking(Dispatchers.Default) {
            previewMutex.withLock {
                stopPreviewPipelineLocked()
            }
            try {
                streamer.release()
            } catch (e: Exception) {
                Log.e(TAG, "release failed", e)
            }
        }
        supervisorJob.cancel()
        runBlocking(Dispatchers.Main) {
            try {
                flutterTexture.release()
            } catch (e: Exception) {
                Log.e(TAG, "release failed", e)
            }
        }
    }

    fun startStream(url: String) {
        Log.d(TAG, "startStream | url=$url")

        runBlocking(Dispatchers.Default) {
            try {
                streamer.open(RtmpMediaDescriptor.fromUrl(url))
                onConnectionSucceeded()
                streamer.startStream()
                _isStreaming = true
                Log.d(TAG, "startStream success")
            } catch (e: Exception) {
                try {
                    streamer.close()
                } catch (_: Exception) {
                }
                val msg = "Failed to start stream: ${e.message}"
                onConnectionFailed(msg)
                Log.e(TAG, "startStream failed", e)
                throw e
            }
        }
    }

    fun stopStream() {
        Log.d(TAG, "stopStream")

        val wasOpen = streamer.isOpenFlow.value
        _isStreaming = false
        runBlocking(Dispatchers.Default) {
            try {
                streamer.stopStream()
            } catch (_: Exception) {
            }
            try {
                streamer.close()
            } catch (_: Exception) {
            }
        }
        if (wasOpen) {
            onDisconnected()
        }
    }

    fun startPreview(onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        Log.d(TAG, "startPreview")

        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                if (_videoConfig == null) {
                    onError(IllegalStateException("Video has not been configured!"))
                } else {
                    scope.launch(Dispatchers.Default) {
                        try {
                            startPreviewSurfaceSuspended()
                            Log.d(TAG, "startPreview success")
                            onSuccess()
                        } catch (e: Exception) {
                            Log.e(TAG, "startPreview failed", e)
                            onError(e)
                        }
                    }
                }
            },
            onRationaleProceed = { _: () -> Unit ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    fun stopPreview() {
        Log.d(TAG, "stopPreview")

        runBlocking(Dispatchers.Default) {
            previewMutex.withLock {
                if (!_isPreviewing && previewSurface == null) {
                    return@withLock
                }
                stopPreviewPipelineLocked()
            }
        }
    }
}
