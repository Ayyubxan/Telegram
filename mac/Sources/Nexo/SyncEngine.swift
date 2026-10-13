import AppKit
import UniformTypeIdentifiers

/// Doimiy ulanish beradigan kanallar (Wi-Fi, Bluetooth).
protocol LinkTransport: AnyObject {
    var isRunning: Bool { get }
    var statusText: String { get }
    /// Hozir shu kanal orqali ulangan (hello yuborgan) qurilmalar
    var connectedPeers: Set<String> { get }
    func start()
    func stop()
    func send(_ sealed: Data, to peers: Set<String>)
}

struct SyncEvent {
    let incoming: Bool
    let channel: Channel?
    let peerName: String
    let preview: String
    let date: Date
    var fileURL: URL? = nil  // qabul qilingan fayl (Finder'da ko'rsatish uchun)
}

/// Barcha ish main thread'da bajariladi.
final class SyncEngine {
    static let maxTextBytes = 1_000_000
    /// ntfy.sh attachment limiti 15 MB; base64 va shifr bilan 10 MB → ~13.4 MB
    static let maxFileBytes = 10_000_000
    /// Bluetooth sekin — bundan katta xabarlar faqat Wi-Fi yoki Internet orqali ketadi
    static let bleMaxBytes = 256 * 1024

    let settings = Settings.shared
    private(set) var key: GroupKey
    let deviceId: String
    let deviceName: String
    let clipboard = ClipboardWatcher()

    lazy var lan = LanTransport(engine: self)
    lazy var ble = BleTransport(engine: self)
    lazy var internet = InternetTransport(engine: self)

    private var seenOrder: [String] = []
    private var seen = Set<String>()
    private var lastClipTs: Int64 = 0
    private var pending: (sealed: Data, ts: Int64)?
    private(set) var lastEvent: SyncEvent?

    var onChange: (() -> Void)?

    init() {
        key = settings.groupKey
        deviceId = settings.deviceId
        deviceName = Host.current().localizedName ?? "Mac"
        clipboard.onCopy = { [weak self] text in self?.localCopy(text) }
        clipboard.onImage = { [weak self] data, mime in self?.localImage(data, mime: mime) }
    }

    func start() {
        clipboard.start()
        applySettings()
    }

    func applySettings() {
        let on = settings.syncEnabled
        toggle(lan, on && settings.lanEnabled)
        toggle(ble, on && settings.bleEnabled)
        if on && settings.internetEnabled { internet.start() } else { internet.stop() }
        changed()
    }

    private func toggle(_ t: LinkTransport, _ on: Bool) {
        if on { t.start() } else { t.stop() }
    }

    func regenerateKey() {
        lan.stop(); ble.stop(); internet.stop()
        key = settings.resetGroupKey()
        applySettings()
    }

    func restartInternet() {
        internet.stop()
        applySettings()
    }

    func handleWake() {
        internet.reconnectNow()
    }

    func changed() { onChange?() }

    var peerNames: [String] { Array(settings.knownPeers.values).sorted() }

    func forgetPeer(_ id: String) {
        settings.knownPeers[id] = nil
        changed()
    }

    var hasActiveLink: Bool { !lan.connectedPeers.isEmpty || !ble.connectedPeers.isEmpty }

    // MARK: - Payloads

    private func seal(_ m: Message) -> Data? {
        guard let json = try? JSONEncoder().encode(m) else { return nil }
        return key.seal(json)
    }

    func helloPayload() -> Data? {
        seal(Message(t: "hello", from: deviceId, name: deviceName, os: "macos"))
    }

    func pingPayload() -> Data? {
        seal(Message(t: "ping", from: deviceId))
    }

    // MARK: - Outgoing

    private func localCopy(_ text: String) {
        guard settings.syncEnabled, text.utf8.count <= Self.maxTextBytes else { return }
        var m = newMessage("clip")
        m.text = text
        lastClipTs = m.ts!
        broadcast(m, preview: preview(text))
    }

    private func localImage(_ data: Data, mime: String) {
        guard settings.syncEnabled, settings.imagesEnabled, data.count <= Self.maxFileBytes else { return }
        var m = newMessage("clip")
        m.mime = mime
        m.data = data
        lastClipTs = m.ts!
        broadcast(m, preview: imagePreview(data))
    }

    /// Tanlangan fayllarni yuboradi. Yuborilmaganlarining nomini qaytaradi (juda katta yoki o'qib bo'lmadi).
    func sendFiles(_ urls: [URL]) -> [String] {
        var skipped: [String] = []
        for url in urls {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? .max
            guard settings.syncEnabled, size <= Self.maxFileBytes,
                  let data = try? Data(contentsOf: url) else {
                skipped.append(url.lastPathComponent)
                continue
            }
            var m = newMessage("file")
            m.file = url.lastPathComponent
            m.mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            m.data = data
            broadcast(m, preview: filePreview(url.lastPathComponent, data))
        }
        return skipped
    }

    private func newMessage(_ t: String) -> Message {
        let mid = UUID().uuidString.lowercased()
        remember(mid)
        return Message(t: t, from: deviceId, name: deviceName, mid: mid, ts: nowMs())
    }

    private func broadcast(_ m: Message, preview: String) {
        guard let sealed = seal(m) else { return }
        let used = route(sealed, origin: nil, cameViaInternet: false)
        if used == nil { pending = (sealed, m.ts ?? nowMs()) }
        lastEvent = SyncEvent(incoming: false, channel: used, peerName: peerNames.joined(separator: ", "), preview: preview, date: Date())
        changed()
    }

    /// Wi-Fi → Bluetooth → Internet tartibida: har bir qurilmaga eng yaxshi mavjud kanal orqali.
    @discardableResult
    private func route(_ sealed: Data, origin: String?, cameViaInternet: Bool) -> Channel? {
        var reached = Set<String>()
        if let origin { reached.insert(origin) }
        var used: Channel?
        for (transport, channel) in [(lan as LinkTransport, Channel.lan), (ble, .ble)]
        where transport.isRunning && (channel != .ble || sealed.count <= Self.bleMaxBytes) {
            let peers = transport.connectedPeers.subtracting(reached)
            guard !peers.isEmpty else { continue }
            transport.send(sealed, to: peers)
            reached.formUnion(peers)
            used = used ?? channel
        }
        let known = Set(settings.knownPeers.keys)
        let unreached = known.subtracting(reached)
        let needInternet = !unreached.isEmpty || (known.isEmpty && origin == nil)
        if internet.isRunning && !cameViaInternet && needInternet {
            internet.publish(sealed)
            used = used ?? .internet
        }
        return used
    }

    /// Yangi qurilma ulanganda, hech kimga yetib bormagan oxirgi clip'ni qayta yuboradi.
    func peerLinked() {
        guard let p = pending, nowMs() - p.ts < 120_000 else { return }
        pending = nil
        route(p.sealed, origin: nil, cameViaInternet: false)
    }

    /// Internet ulanganda — clipboard uchun qo'shimcha ish yo'q
    func internetLinked() {}

    // MARK: - Incoming

    /// Kanallar har bir kelgan payload'ni shu yerga beradi. Kalit mos kelmasa nil qaytadi.
    @discardableResult
    func receive(_ sealed: Data, via channel: Channel) -> Message? {
        guard let plain = key.open(sealed), let msg = try? JSONDecoder().decode(Message.self, from: plain) else { return nil }
        guard msg.from != deviceId else { return msg }
        if let name = msg.name, settings.knownPeers[msg.from] != name {
            settings.knownPeers[msg.from] = name
            changed()
        }
        switch msg.t {
        case "clip": handleClip(msg, sealed: sealed, channel: channel)
        case "file": handleFile(msg, sealed: sealed, channel: channel)
        default: break
        }
        return msg
    }

    private func handleClip(_ msg: Message, sealed: Data, channel: Channel) {
        guard let mid = msg.mid, let ts = msg.ts, msg.text != nil || msg.data != nil, !seen.contains(mid) else { return }
        remember(mid)
        guard settings.syncEnabled else { return }
        // Bir nechta telefon bo'lsa — boshqalariga ham uzatamiz
        route(sealed, origin: msg.from, cameViaInternet: channel == .internet)
        guard ts >= lastClipTs else { return }
        let shown: String
        if let text = msg.text {
            clipboard.write(text)
            shown = preview(text)
        } else if let data = msg.data, settings.imagesEnabled, clipboard.writeImage(data, mime: msg.mime) {
            shown = imagePreview(data)
        } else {
            return
        }
        lastClipTs = ts
        lastEvent = SyncEvent(incoming: true, channel: channel, peerName: msg.name ?? "Telefon", preview: shown, date: Date())
        changed()
    }

    private func handleFile(_ msg: Message, sealed: Data, channel: Channel) {
        guard let mid = msg.mid, let data = msg.data, !seen.contains(mid) else { return }
        remember(mid)
        guard settings.syncEnabled else { return }
        route(sealed, origin: msg.from, cameViaInternet: channel == .internet)
        guard let url = saveDownload(data, name: msg.file) else { return }
        let peer = msg.name ?? "Telefon"
        lastEvent = SyncEvent(incoming: true, channel: channel, peerName: peer,
                              preview: filePreview(url.lastPathComponent, data), date: Date(), fileURL: url)
        Notifier.shared.fileReceived(url, from: peer)
        changed()
    }

    /// ~/Downloads ga saqlaydi; nom band bo'lsa «nom 2.ext», «nom 3.ext»...
    private func saveDownload(_ data: Data, name: String?) -> URL? {
        let fm = FileManager.default
        guard let dir = fm.urls(for: .downloadsDirectory, in: .userDomainMask).first else { return nil }
        let safe = sanitizeFileName(name)
        let base = (safe as NSString).deletingPathExtension
        let ext = (safe as NSString).pathExtension
        var url = dir.appendingPathComponent(safe)
        var n = 2
        while fm.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        do {
            try data.write(to: url, options: .withoutOverwriting)
            return url
        } catch {
            NSLog("Nexo: faylni saqlab bo'lmadi: \(error)")
            return nil
        }
    }

    private func remember(_ mid: String) {
        seen.insert(mid)
        seenOrder.append(mid)
        if seenOrder.count > 500 {
            seen.remove(seenOrder.removeFirst())
        }
    }

    private func imagePreview(_ data: Data) -> String { "🖼 Rasm · \(sizeLabel(data.count))" }

    private func filePreview(_ name: String, _ data: Data) -> String { "📄 \(name) · \(sizeLabel(data.count))" }

    private func preview(_ text: String) -> String {
        let oneLine = text.split(whereSeparator: \.isNewline).joined(separator: " ")
        return oneLine.count > 80 ? String(oneLine.prefix(80)) + "…" : oneLine
    }

    // MARK: - Pairing

    var pairingURI: String {
        var c = URLComponents()
        c.scheme = "nexo"
        c.host = "pair"
        c.queryItems = [
            URLQueryItem(name: "k", value: key.base64url),
            URLQueryItem(name: "n", value: deviceName),
            URLQueryItem(name: "h", value: localIPv4Addresses().map { "\($0):\(lan.port)" }.joined(separator: ",")),
            URLQueryItem(name: "r", value: settings.relayServer),
        ]
        return c.string ?? ""
    }
}

func sizeLabel(_ bytes: Int) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
}

/// Boshqa qurilmadan kelgan nomdan papka yo'li va yashirin fayl belgilarini olib tashlaydi.
func sanitizeFileName(_ name: String?) -> String {
    let last = (name ?? "").split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
    let cleaned = last.replacingOccurrences(of: ":", with: "_")
        .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
    return cleaned.isEmpty ? "fayl" : String(cleaned.prefix(120))
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func localIPv4Addresses() -> [String] {
    var result: [String] = []
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
    defer { freeifaddrs(ifaddr) }
    for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let ifa = ptr.pointee
        guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
        let flags = Int32(ifa.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
        let name = String(cString: ifa.ifa_name)
        guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            result.append(String(cString: host))
        }
    }
    return result
}
