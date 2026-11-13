import SwiftUI
import Foundation

private struct RadioCountry: Decodable, Identifiable {
    let name: String
    let iso_3166_1: String
    let stationcount: Int?
    var id: String { iso_3166_1 }

    var flag: String {
        let code = id.uppercased()
        guard code.utf8.count == 2, code.utf8.allSatisfy({ (65...90).contains($0) }) else { return "" }
        return String(String.UnicodeScalarView(code.unicodeScalars.map {
            UnicodeScalar(0x1F1E6 + $0.value - 65)!
        }))
    }
}

private struct RadioStation: Decodable, Identifiable {
    let stationuuid: String
    let name: String
    let country: String
    let url_resolved: String
    let favicon: String?
    let codec: String?
    let tags: String?
    let bitrate: Int?
    var id: String { stationuuid }

    var playableURL: URL? {
        guard let url = URL(string: url_resolved),
              ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil, url.user == nil, url.password == nil else { return nil }
        return url
    }

    var subtitle: String {
        let genres = (tags ?? "").split(separator: ",").map { tag in
            let value = tag.trimmingCharacters(in: .whitespacesAndNewlines)
            return value.prefix(1).uppercased() + String(value.dropFirst())
        }.filter { !$0.isEmpty }.prefix(2).joined(separator: " · ")
        let format = [codec?.trimmingCharacters(in: .whitespacesAndNewlines),
                      bitrate.flatMap { $0 > 0 ? "\($0) kbps" : nil }]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return [genres, format].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}


struct RadioView: View {
    @Environment(PlaybackController.self) private var controller
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var countries: [RadioCountry] = []
    @State private var stations: [RadioStation] = []
    @State private var country = Locale.current.region?.identifier ?? "US"
    @State private var query = ""
    @State private var submitted = ""
    @State private var problem: String?
    @State private var loading = true
    @State private var retry = 0
    private var requestKey: String { "\(country)|\(submitted)|\(retry)" }
    private let hosts = ["all.api.radio-browser.info", "de1.api.radio-browser.info", "fi1.api.radio-browser.info"]

    var body: some View {
        PageStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        Text("Radio").font(.largeTitle.weight(.heavy))
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .font(.title.weight(.semibold))
                            .accessibilityHidden(true)
                    }
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search stations", text: $query)
                            .textFieldStyle(.plain)
                            .submitLabel(.search)
                            .onSubmit { submitted = query.trimmingCharacters(in: .whitespacesAndNewlines) }
                            .onChange(of: query) { _, value in
                                if value.isEmpty { submitted = "" }
                            }
                        if !query.isEmpty {
                            Button("Clear", systemImage: "xmark.circle.fill") {
                                query = ""; submitted = ""
                            }
                            .labelStyle(.iconOnly)
                            .buttonStyle(.plain)
                            .minimumHitTarget()
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 48)
                    .background(Theme.Colors.surfaceVariant, in: Capsule())
                    if submitted.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(countries) { item in
                                    Button {
                                        country = item.id
                                    } label: {
                                         Text("\(item.flag) \(item.name)".trimmingCharacters(in: .whitespaces))
                                            .font(.subheadline.weight(.semibold))
                                            .padding(.horizontal, 16)
                                            .frame(minHeight: 44)
                                            .background(country == item.id ? Color.primary.opacity(0.14) : Color.clear, in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityAddTraits(country == item.id ? [.isSelected] : [])
                                }
                            }
                        }
                    }
                    if loading {
                        ProgressView("Loading stations…")
                            .frame(maxWidth: .infinity, minHeight: 160)
                    } else if let problem {
                        ContentUnavailableView {
                            Label("Radio couldn’t load", systemImage: "antenna.radiowaves.left.and.right")
                        } description: {
                            Text(problem)
                        } actions: {
                            Button("Retry") { retry += 1 }
                        }
                    } else if stations.isEmpty {
                        ContentUnavailableView.search(text: submitted)
                    } else {
                        Text(submitted.isEmpty ? "Popular stations" : "Search results")
                            .font(.headline)
                        ForEach(stations) { station in
                            stationRow(station)
                        }
                    }
                }
                .padding(24)
            }
            .background(Theme.Colors.background)
            .navigationTitle("Radio")
            .toolbar {
                #if os(iOS)
                TopBarLeadingItems()
                ToolbarItem(placement: .topBarTrailing) { TopBarAccountButton() }
                #endif
            }
            .task { await loadCountries() }
            .task(id: requestKey) { await loadStations() }
            .refreshable { retry += 1 }
        }
    }

    private func stationRow(_ station: RadioStation) -> some View {
        Button {
            guard let url = station.playableURL else { return }
            Task { try? await requestData("url/\(station.id)", query: []) }
            let entry = QueueEntry(
                id: "radio:\(station.id)", title: station.name, artist: station.country,
                source: url.absoluteString, thumbnailUrl: station.favicon,
                durationText: nil, albumName: "Live radio", artworkData: nil, isLocal: true
            )
            controller.play([entry], at: 0)
        } label: {
            HStack(spacing: 14) {
                ArtworkView(url: station.favicon, data: nil, side: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 4) {
                    Text(station.name).font(.body.weight(.semibold)).lineLimit(1)
                    Text(station.subtitle.isEmpty ? station.country : station.subtitle)
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: controller.current?.id == "radio:\(station.id)" && controller.isPlaying ? "waveform" : "play.fill")
                    .font(.body.weight(.semibold))
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play \(station.name), \(station.country)")
        .disabled(station.playableURL == nil)
    }

    private func loadCountries() async {
        guard countries.isEmpty else { return }
        do {
            var result: [RadioCountry] = try await fetch("countries", query: [
                URLQueryItem(name: "order", value: "stationcount"),
                URLQueryItem(name: "reverse", value: "true"),
                URLQueryItem(name: "hidebroken", value: "true")
            ])
            result.removeAll { $0.id.count != 2 || ($0.stationcount ?? 0) <= 0 }
            if let home = result.firstIndex(where: { $0.id == country }) {
                result.swapAt(0, home)
            }
            if !result.contains(where: { $0.id == country }), let first = result.first { country = first.id }
            countries = result
        } catch {

        }
    }

    private func loadStations() async {
        let key = requestKey
        loading = true; problem = nil
        var parameters = [URLQueryItem(name: "hidebroken", value: "true"),
                           URLQueryItem(name: "order", value: "clickcount"),
                           URLQueryItem(name: "reverse", value: "true")]
        let path: String
        if submitted.isEmpty {
            path = "stations/bycountrycodeexact/\(country)"
            parameters.append(URLQueryItem(name: "limit", value: "150"))
        } else {
            path = "stations/search"
            parameters.append(contentsOf: [
                URLQueryItem(name: "limit", value: "100"),
                URLQueryItem(name: "name", value: submitted)
            ])
        }
        do {
            let result: [RadioStation] = try await fetch(path, query: parameters)
            guard !Task.isCancelled, key == requestKey else { return }
            var seen = Set<String>()
            stations = result.filter {
                guard let url = $0.playableURL else { return false }
                return seen.insert(url.absoluteString).inserted
            }
            loading = false
        } catch {
            guard !Task.isCancelled, key == requestKey else { return }
            loading = false; problem = error.localizedDescription
        }
    }

    private func fetch<T: Decodable>(_ path: String, query: [URLQueryItem]) async throws -> T {
        try JSONDecoder().decode(T.self, from: await requestData(path, query: query))
    }

    private func requestData(_ path: String, query: [URLQueryItem]) async throws -> Data {
        var lastError: Error?
        for host in hosts {
            do {
                var components = URLComponents(string: "https://\(host)/json/\(path)")!
                if !query.isEmpty { components.queryItems = query }
                var request = URLRequest(url: components.url!, timeoutInterval: 15)
                request.setValue("nuvx/0.8.0", forHTTPHeaderField: "User-Agent")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                return data
            } catch {
                if Task.isCancelled { throw CancellationError() }
                lastError = error
            }
        }
        throw lastError ?? URLError(.cannotConnectToHost)
    }
}
