import Foundation



func localTrackComesBefore(
    trackNumber lhsNumber: UInt32,
    path lhsPath: String,
    trackNumber rhsNumber: UInt32,
    path rhsPath: String
) -> Bool {
    if lhsNumber > 0, rhsNumber > 0, lhsNumber != rhsNumber {
        return lhsNumber < rhsNumber
    }
    if (lhsNumber > 0) != (rhsNumber > 0) { return lhsNumber > 0 }
    return lhsPath.localizedStandardCompare(rhsPath) == .orderedAscending
}
