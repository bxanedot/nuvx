import AVFoundation
import Foundation



enum CanvasMediaRequest {
    static let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    static func download(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")

        request.httpShouldHandleCookies = false
        return request
    }

    static func asset(_ url: URL) -> AVURLAsset {
        AVURLAsset(url: url, options: url.isFileURL ? nil : [AVURLAssetHTTPUserAgentKey: userAgent])
    }
}
