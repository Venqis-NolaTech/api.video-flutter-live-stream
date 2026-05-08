package video.api.flutter.livestream

import android.Manifest
import android.content.Context
import android.util.Size
import android.view.Surface
import io.flutter.view.TextureRegistry
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FlutterLiveStreamView(
    private val context: Context,
    textureRegistry: TextureRegistry,
    private val permissionsManager: PermissionsManager,
    private val onConnectionSucceeded: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onConnectionFailed: (String) -> Unit,
    private val onGenericError: (Exception) -> Unit,
    private val onVideoSizeChanged: (Size) -> Unit,
) {
    private val flutterTexture = textureRegistry.createSurfaceTexture()
    val textureId: Long
        get() = flutterTexture.id()

    private val streamer: SingleStreamer = runBlocking {
        cameraSingleStreamer(
            context,
            endpointFactory = RtmpEndpointFactory(),
        )
    }

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Main.immediate)

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
        scope.launch {
            streamer.throwableFlow
                .filterNotNull()
                .collect { t ->
                    _isStreaming = false
                    onGenericError(t as? Exception ?: Exception(t.message, t))
                }
        }
        scope.launch {
            streamer.isOpenFlow
                .filter { !it }
                .collect {
                    if (_isStreaming) {
                        _isStreaming = false
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
        if (_isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        onVideoSizeChanged(videoConfig.resolution)

        val wasPreviewing = _isPreviewing
        if (wasPreviewing) {
            stopPreview()
        }
        try {
            runBlocking {
                streamer.setVideoConfig(videoConfig)
            }
            _videoConfig = videoConfig
            if (wasPreviewing) {
                startPreview(onSuccess, onError)
            } else {
                onSuccess()
            }
        } catch (e: Exception) {
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
        if (_isStreaming) {
            throw UnsupportedOperationException("You have to stop streaming first")
        }

        permissionsManager.requestPermission(
            Manifest.permission.RECORD_AUDIO,
            onGranted = {
                try {
                    runBlocking {
                        streamer.setAudioConfig(audioConfig)
                    }
                    _audioConfig = audioConfig
                    onSuccess()
                } catch (e: Exception) {
                    onError(e)
                }
            },
            onShowPermissionRationale = { _ ->
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
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                try {
                    runBlocking {
                        streamer.setCameraId(camera)
                    }
                    onSuccess()
                } catch (e: Exception) {
                    onError(e)
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    val cameraPosition: String
        get() = when {
            context.cameraManager.isFrontCamera(cameraId) -> "front"
            context.cameraManager.isBackCamera(cameraId) -> "back"
            context.cameraManager.isExternalCamera(cameraId) -> "other"
            else -> throw IllegalArgumentException("Invalid camera position for camera $cameraId")
        }

    fun setCameraPosition(position: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        val cameraList = when (position) {
            "front" -> context.cameraManager.frontCameras
            "back" -> context.cameraManager.backCameras
            "other" -> context.cameraManager.externalCameras
            else -> throw IllegalArgumentException("Invalid camera position: $position")
        }
        setCamera(cameraList.first(), onSuccess, onError)
    }

    fun dispose() {
        stopStream()
        runBlocking {
            try {
                streamer.stopPreview()
            } catch (_: Exception) {
            }
            try {
                streamer.release()
            } catch (_: Exception) {
            }
        }
        supervisorJob.cancel()
        flutterTexture.release()
    }

    fun startStream(url: String) {
        runBlocking {
            try {
                streamer.open(RtmpMediaDescriptor.fromUrl(url))
                onConnectionSucceeded()
                streamer.startStream()
                _isStreaming = true
            } catch (e: Exception) {
                try {
                    streamer.close()
                } catch (_: Exception) {
                }
                onConnectionFailed("Failed to start stream: ${e.message}")
                throw e
            }
        }
    }

    fun stopStream() {
        val wasOpen = streamer.isOpenFlow.value
        _isStreaming = false
        runBlocking {
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
        permissionsManager.requestPermission(
            Manifest.permission.CAMERA,
            onGranted = {
                if (_videoConfig == null) {
                    onError(IllegalStateException("Video has not been configured!"))
                } else {
                    try {
                        runBlocking {
                            streamer.startPreview(getSurface(videoConfig.resolution))
                        }
                        _isPreviewing = true
                        onSuccess()
                    } catch (e: Exception) {
                        onError(e)
                    }
                }
            },
            onShowPermissionRationale = { _ ->
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            },
            onDenied = {
                onError(SecurityException("Missing permission Manifest.permission.CAMERA"))
            })
    }

    fun stopPreview() {
        runBlocking {
            try {
                streamer.stopPreview()
            } catch (_: Exception) {
            }
        }
        _isPreviewing = false
    }

    private fun getSurface(resolution: Size): Surface {
        val surfaceTexture = flutterTexture.surfaceTexture().apply {
            setDefaultBufferSize(
                resolution.width,
                resolution.height
            )
        }
        return Surface(surfaceTexture)
    }
}
