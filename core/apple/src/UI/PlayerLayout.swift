import CoreGraphics
import Foundation


















enum PlayerLayout {

    static func containedCanvasSize(bounds: CGSize, aspect: CGFloat) -> CGSize {
        guard bounds.width > 0, bounds.height > 0, aspect > 0, aspect.isFinite else { return .zero }
        let width = min(bounds.width, bounds.height * aspect)
        return CGSize(width: width, height: width / aspect)
    }










    static let landscapeMinWidth: CGFloat = 560




    static let landscapeCompactHeight: CGFloat = 440







    static let landscapeMaxWidth: CGFloat = 1100



    static let ipadLandscapeMaxWidth: CGFloat = 1280



    static let macLandscapeMaxWidth: CGFloat = 1440


    static let landscapeGutter: CGFloat = 30
    static let landscapeGutterCompact: CGFloat = 20








    static func takesLandscapeShape(width: CGFloat, height: CGFloat) -> Bool {
        width > height && width >= landscapeMinWidth
    }


    static func isCompactLandscape(width: CGFloat, height: CGFloat) -> Bool {
        height < landscapeCompactHeight
    }


    static func gutter(compact: Bool) -> CGFloat {
        compact ? landscapeGutterCompact : landscapeGutter
    }






















    static func putsPaneBesideStage(width: CGFloat, regularWidth: Bool) -> Bool {
        regularWidth && width >= landscapeMinWidth
    }







    static let sleeveCollapsedSide: CGFloat = 56








    static func portraitArtworkSide(
        stageWidth: CGFloat,
        stageHeight: CGFloat,
        maximumSide: CGFloat? = nil
    ) -> CGFloat {
        let widthLimit = min(stageWidth - 48, maximumSide ?? .greatestFiniteMagnitude)
        let fitted = min(widthLimit, stageHeight - 20)
        return max(sleeveCollapsedSide, fitted)
    }



    static let ipadPortraitArtworkMaxSide: CGFloat = 600
    static let ipadPortraitArtworkWidthFraction: CGFloat = 0.62





    static let fullBleedMaxWidth: CGFloat = 620



    static let tabletMinWidth: CGFloat = 700




    static func fullBleedArtworkAvailable(width: CGFloat) -> Bool {
        width <= fullBleedMaxWidth || width >= tabletMinWidth
    }


    static func usesFullBleedArtwork(width: CGFloat, preferenceEnabled: Bool) -> Bool {
        preferenceEnabled && fullBleedArtworkAvailable(width: width)
    }

}
