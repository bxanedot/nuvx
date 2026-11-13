import Foundation
import NuvxShared
import os
#if os(iOS)
import UIKit
#if DEBUG
import AVFoundation
#endif
#else
import AppKit
#endif


actor AutomixQueueRanker {
    static let shared = AutomixQueueRanker()
    func rank(currentPath: String, candidatePaths: [String], currentText: String,
              candidateTexts: [String], crossfadeSeconds: Double, skipVocals: Bool) -> [UInt32] {
        guard !Task.isCancelled else { return [] }
        return rankAutomixCandidates(
            currentPath: currentPath, candidatePaths: candidatePaths,
            currentText: currentText, candidateTexts: candidateTexts,
            crossfadeSeconds: crossfadeSeconds, skipVocals: skipVocals
        )
    }
}
