import ApiVideoLiveStream
import AVFoundation
import Foundation

class FlutterLiveStreamView: NSObject {
    private let previewTexture: PreviewTexture
    private let liveStream: ApiVideoLiveStream

    private let eventChannel: FlutterEventChannel
    private var eventSink: FlutterEventSink?

    init(binaryMessenger: FlutterBinaryMessenger, textureRegistry: FlutterTextureRegistry) throws {
        previewTexture = PreviewTexture(registry: textureRegistry)
        liveStream = try ApiVideoLiveStream(preview: previewTexture, initialAudioConfig: nil, initialVideoConfig: nil, initialCamera: nil)
        eventChannel = FlutterEventChannel(name: "video.api.livestream/events", binaryMessenger: binaryMessenger)

        super.init()

        liveStream.delegate = self
        eventChannel.setStreamHandler(self)
    }

    var textureId: Int64 {
        previewTexture.textureId
    }

    private(set) var isStreaming = false

    // The SDK already attaches the microphone on creation, so the preview is considered running.
    // Starting it again would re-attach the microphone on the running capture session.
    private var isPreviewRunning = true

    // Cached values: reading them from the SDK does a `lockQueue.sync` on HaishinKit's IOStream queue,
    // which blocks the main thread while the capture session is being (re)configured.
    private var currentVideoConfig = VideoConfig()
    private var currentCameraPosition = "other"

    var videoConfig: VideoConfig {
        get {
            currentVideoConfig
        }
        set {
            sendEvent(["type": "videoSizeChanged", "width": Double(newValue.resolution.width), "height": Double(newValue.resolution.height)])

            currentVideoConfig = newValue
            liveStream.videoConfig = newValue
        }
    }

    var audioConfig: AudioConfig {
        get {
            liveStream.audioConfig
        }
        set {
            liveStream.audioConfig = newValue
        }
    }

    var isMuted: Bool {
        get {
            liveStream.isMuted
        }
        set {
            liveStream.isMuted = newValue
        }
    }

    var cameraPosition: String {
        get {
            currentCameraPosition
        }
        set {
            if newValue == "back" {
                liveStream.cameraPosition = AVCaptureDevice.Position.back
                currentCameraPosition = newValue
            } else if newValue == "front" {
                liveStream.cameraPosition = AVCaptureDevice.Position.front
                currentCameraPosition = newValue
            }
        }
    }

    func dispose() {
        liveStream.stopStreaming()
        liveStream.stopPreview()

        previewTexture.dispose()
    }

    func startPreview() {
        guard !isPreviewRunning else {
            return
        }
        liveStream.startPreview()
        isPreviewRunning = true
    }

    func stopPreview() {
        liveStream.stopPreview()
        isPreviewRunning = false
    }

    func startStreaming(streamKey: String, url: String) throws {
        try liveStream.startStreaming(streamKey: streamKey, url: url)
        isStreaming = true
    }

    func stopStreaming() {
        liveStream.stopStreaming()
        isStreaming = false
    }
}

extension FlutterLiveStreamView: FlutterStreamHandler {
    func onListen(withArguments _: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    func onCancel(withArguments _: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }
    
    private func sendEvent(_ event: [String: Any]) {
        DispatchQueue.main.async {
            self.eventSink?(event)
        }
    }
}

extension FlutterLiveStreamView: ApiVideoLiveStreamDelegate {
    /// Called when the connection to the rtmp server is successful
    func connectionSuccess() {
        sendEvent(["type": "connected"])
    }

    /// Called when the connection to the rtmp server failed
    func connectionFailed(_: String) {
        isStreaming = false
        sendEvent(["type": "connectionFailed", "message": "Failed to connect"])
    }

    /// Called when the connection to the rtmp server is closed
    func disconnection() {
        isStreaming = false
        sendEvent(["type": "disconnected"])
    }

    /// Called if an error happened during the audio configuration
    func audioError(_ error: Error) {
        print("audio error: \(error)")
    }

    /// Called if an error happened during the video configuration
    func videoError(_ error: Error) {
        print("video error: \(error)")
    }
}
