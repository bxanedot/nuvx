#!/bin/bash
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
require() {
  local file="$1" pattern="$2"
  if ! grep -Fq "$pattern" "$repo/$file"; then
    echo "missing settings parity contract: $file: $pattern" >&2
    exit 1
  fi
}

require ../../core/apple/src/UI/Settings/SettingsView+Playback.swift 'rows.row("Phrase Length"'
require ../../core/apple/src/UI/Settings/SettingsView+Playback.swift 'controller.setAutomixPhraseBars($0)'
require ../../core/apple/src/UI/Settings/SettingsView+Playback.swift 'Text("Adaptive").tag(0)'
require ../../core/apple/src/UI/Settings/SettingsView+Playback.swift 'Text("8 bars").tag(8)'
require ../../core/apple/src/UI/Settings/SettingsView+Playback.swift 'Text("16 bars").tag(16)'
require ../../core/apple/src/App/SettingsDeepLink.swift 'url.host?.lowercased() == "settings"'
require ../../core/apple/src/App/SettingsDeepLink.swift '?? SettingsView.SettingsSection.account.rawValue'
require ../../core/apple/src/App/SettingsDeepLink.swift 'openSettings()'
require ../../core/apple/src/UI/Settings/SettingsView.swift 'proxy.scrollTo(section, anchor: .top)'
require ../../core/apple/src/UI/Settings/AudioSettingsCatalog.swift 'return "Loudness Normalization"'
require ../../core/apple/src/UI/Settings/AudioSettingsPageView.swift 'SettingsSubToggle(title: "Off on iPhone Speaker"'
require ../../core/apple/src/UI/Settings/AudioSettingsPageView.swift 'Text("Track").tag("TRACK")'
require ../../core/apple/src/UI/Settings/AudioSettingsPageView.swift 'Text("Album").tag("ALBUM")'
require ../../core/apple/src/UI/Settings/SettingsView.swift 'controller.updateLoudnessMode(controller.loudnessMode)'
require ../../core/apple/src/UI/Settings/SettingsView.swift 'private var macSettingsSplitView: some View'
require ../../core/apple/src/UI/Settings/SettingsMacSplitView.swift 'NavigationSplitView'
require ../../core/apple/src/UI/Settings/SettingsMacSplitView.swift '.navigationSplitViewColumnWidth(min: 190, ideal: 240)'
require ../../core/apple/src/UI/Settings/SettingsPersistence.swift 'value ? loudnessModeSelection : "OFF"'
require ../../core/apple/src/UI/Settings/SettingsView+About.swift 'rows.row("Hide Status Bar in Player"'
require ../../core/apple/src/UI/Settings/SettingsView+About.swift 'rows.row("Show Queue History"'
require ../../core/apple/src/UI/Settings/SettingsView+About.swift 'rows.row("Stop Music on Close"'
require ../../core/apple/src/UI/Settings/SettingsView+Storage.swift 'rows.row("Show Cached Songs in Library"'
require ../../core/apple/src/UI/Settings/SettingsView+Appearance.swift 'rows.row("Artist Signatures"'
require ../../core/apple/src/UI/Settings/SettingsView+Appearance.swift 'AppSettings.shared.setArtistSignaturesEnabled(value: value)'
require ../../core/apple/src/UI/Settings/SettingsView+Appearance.swift 'AppSettings.shared.setArtistSignaturesWikimediaUrl(value: value)'
require ../../core/apple/src/UI/NowPlaying/NowPlayingView.swift '.statusBarHidden(hideStatusBarInPlayer)'
require ../../core/apple/src/UI/NowPlaying/UpNextPane.swift 'Text("Previously Played")'
require ../../core/apple/src/UI/SourcesView.swift 'Text("Track Length Tolerance")'
require shared/src/commonMain/kotlin/com/bxane/nuvx/data/settings/AppSettings.kt 'fun setUpgradeLengthSlackSeconds(value: Int)'
require shared/src/commonMain/kotlin/com/bxane/nuvx/data/settings/AppSettings.kt 'KEY_UPGRADE_LENGTH_SLACK_SECONDS'
require shared/src/commonTest/kotlin/com/bxane/nuvx/data/settings/UpgradeLengthSlackTest.kt 'slack is bounded persisted and included in settings backup'
require shared/src/commonTest/kotlin/com/bxane/nuvx/data/settings/UpgradeLengthSlackTest.kt 'artist signature preferences persist and restore'
require ../../core/apple/src/UI/SearchView.swift 'typeaheadMaxResults = 15'
require ../../core/apple/src/UI/SearchView.swift 'TYPEAHEAD_MAX_RESULTS'
require ../../core/apple/src/UI/SearchView.swift 'typeaheadHits.prefix(Self.typeaheadMaxResults)'

echo "settings parity source contracts passed"
