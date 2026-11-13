import SwiftUI
import NuvxShared
import UniformTypeIdentifiers
import AVKit
import CoreImage
import CoreImage.CIFilterBuiltins
#if os(iOS)
import UIKit
import AVFAudio
#else
import AppKit
#endif









struct LyricShareCard: View {


    struct Line {
        var text: String


        var subText: String? = nil
        var isGap: Bool = false
    }

    let lines: [Line]
    let title: String
    let artist: String
    let tint: ArtworkPalette.PageTint
    let artwork: Data?
    let artworkUrl: String?
    let storyFormat: Bool

    private var quoteCharacterCount: Int {
        lines.filter { !$0.isGap }.reduce(0) { $0 + $1.text.count }
    }

    private var lyricFontSize: CGFloat {
        switch quoteCharacterCount {
        case ..<80: 36
        case ..<160: 32
        default: 28
        }
    }

    private var cardHeight: CGFloat? {
        storyFormat ? 960 : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: storyFormat ? 22 : 18) {
            HStack(spacing: 12) {
                ArtworkView(url: artworkUrl, data: artwork, side: 56)
                    .clipShape(.rect(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(artist)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    if line.isGap {





                        Text("(…)")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.4))
                    } else {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(line.text)
                                .font(.system(size: lyricFontSize, weight: .semibold))
                                .foregroundStyle(.white)
                                .fixedSize(horizontal: false, vertical: true)
                            if let sub = line.subText {
                                Text(sub)
                                    .font(.system(size: lyricFontSize * 0.68, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.72))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .environment(
                            \.layoutDirection,
                            lyricTextIsRightToLeft(line.text) ? .rightToLeft : .leftToRight
                        )
                    }
                }
            }

            Spacer(minLength: 0)

            HStack {
                Text("nuvx")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.55))
                Spacer(minLength: 8)
                Text(artist)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
                    .lineLimit(1)
            }
        }
        .padding(28)
        .frame(width: 540, height: cardHeight, alignment: .topLeading)
        .background {
            ZStack {
                tint.wash
                LinearGradient(
                    colors: [tint.accent.opacity(0.38), tint.wash, .black.opacity(0.86)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            }
        }
    }
}
