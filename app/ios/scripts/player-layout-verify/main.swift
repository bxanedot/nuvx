import CoreGraphics
import Foundation







var failures = 0
var checks = 0

func check(_ label: String, _ ok: Bool, _ detail: String = "") {
    checks += 1
    if ok {
        print("  ok   \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    } else {
        failures += 1
        print("  FAIL \(label)\(detail.isEmpty ? "" : " — \(detail)")")
    }
}

func landscape(_ label: String, _ width: CGFloat, _ height: CGFloat, _ want: Bool) {
    let got = PlayerLayout.takesLandscapeShape(width: width, height: height)
    check(
        "\(label) (\(Int(width))×\(Int(height))) is \(want ? "landscape" : "portrait")",
        got == want
    )
}

print("player layout: which shape a window takes")




landscape("a phone upright", 393, 852, false)


landscape("a phone on its side", 852, 393, true)



landscape("a tablet upright", 820, 1180, false)


landscape("a tablet on its side", 1180, 820, true)


landscape("a Mac window", 1440, 900, true)



landscape("a square window", 600, 600, false)





landscape("just below the floor", PlayerLayout.landscapeMinWidth - 1, 300, false)
landscape("exactly the floor", PlayerLayout.landscapeMinWidth, 300, true)
landscape("just above the floor", PlayerLayout.landscapeMinWidth + 1, 300, true)




landscape("narrow but wider than tall", 300, 200, false)







check(
    "a tablet upright puts an open pane beside the stage",
    PlayerLayout.putsPaneBesideStage(width: 820, regularWidth: true)
)
check(
    "a square tablet window does too — the rule is width, not orientation",
    PlayerLayout.putsPaneBesideStage(width: 600, regularWidth: true)
)



check(
    "a compact-width window does not",
    !PlayerLayout.putsPaneBesideStage(width: 820, regularWidth: false)
)


check(
    "a phone upright does not",
    !PlayerLayout.putsPaneBesideStage(width: 393, regularWidth: true)
)



check(
    "one point below the floor does not",
    !PlayerLayout.putsPaneBesideStage(
        width: PlayerLayout.landscapeMinWidth - 1, regularWidth: true
    )
)
check(
    "exactly at the floor it does",
    PlayerLayout.putsPaneBesideStage(
        width: PlayerLayout.landscapeMinWidth, regularWidth: true
    )
)





check(
    "a phone on its side is compact",
    PlayerLayout.isCompactLandscape(width: 852, height: 393)
)
check(
    "a Mac window is not compact",
    !PlayerLayout.isCompactLandscape(width: 1440, height: 900)
)
check(
    "a window one point below the compact height is compact",
    PlayerLayout.isCompactLandscape(width: 900, height: PlayerLayout.landscapeCompactHeight - 1)
)
check(
    "a window exactly at the compact height is not",
    !PlayerLayout.isCompactLandscape(width: 900, height: PlayerLayout.landscapeCompactHeight)
)



check("compact uses a smaller gutter",
      PlayerLayout.gutter(compact: true) < PlayerLayout.gutter(compact: false),
      "\(Int(PlayerLayout.gutter(compact: true))) < \(Int(PlayerLayout.gutter(compact: false)))")
check("both gutters are positive",
      PlayerLayout.gutter(compact: true) > 0 && PlayerLayout.gutter(compact: false) > 0)



check("the landscape player has a width ceiling", PlayerLayout.landscapeMaxWidth > 0)
check("the ceiling is wider than the floor",
      PlayerLayout.landscapeMaxWidth > PlayerLayout.landscapeMinWidth,
      "\(Int(PlayerLayout.landscapeMaxWidth)) > \(Int(PlayerLayout.landscapeMinWidth))")




let tall = PlayerLayout.portraitArtworkSide(stageWidth: 393, stageHeight: 600)
check("a tall stage fits an artwork to its width", abs(tall - (393 - 48)) < 0.5,
      "\(Int(tall)) of \(393 - 48) available")



let short = PlayerLayout.portraitArtworkSide(stageWidth: 393, stageHeight: 200)
check("a short stage fits the artwork to its height", abs(short - (200 - 20)) < 0.5,
      "\(Int(short)) of \(200 - 20) available")
check("a short stage shrinks the artwork", short < tall,
      "\(Int(short)) < \(Int(tall))")


check("the artwork is never wider than its stage",
      PlayerLayout.portraitArtworkSide(stageWidth: 300, stageHeight: 900) <= 300)
check("the artwork is never taller than its stage",
      PlayerLayout.portraitArtworkSide(stageWidth: 900, stageHeight: 100) <= 100)




let squeezed = PlayerLayout.portraitArtworkSide(stageWidth: 200, stageHeight: 30)
check("a squeezed stage still shows an artwork", squeezed > 0, "\(Int(squeezed))")
check("a squeezed stage does not show a bigger artwork than the collapsed one",
      squeezed <= PlayerLayout.sleeveCollapsedSide,
      "\(Int(squeezed)) <= \(Int(PlayerLayout.sleeveCollapsedSide))")


check("the collapsed sleeve is a usable target",
      PlayerLayout.sleeveCollapsedSide >= 44,
      "\(Int(PlayerLayout.sleeveCollapsedSide))pt — 44 is Apple's minimum")


for (w, h) in [(CGFloat(1), CGFloat(1)), (100, 50), (3000, 2000), (5000, 10)] {
    let side = PlayerLayout.portraitArtworkSide(stageWidth: w, stageHeight: h)
    check("no negative artwork for \(Int(w))×\(Int(h))", side > 0, "\(Int(side))")
}

print(failures == 0 ? "\nall \(checks) checks passed" : "\n\(failures) of \(checks) checks FAILED")
exit(failures == 0 ? 0 : 1)
