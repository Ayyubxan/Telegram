import Foundation
import Network

/// Wi-Fi: Mac TCP server bo'lib turadi va Bonjour orqali e'lon qiladi, telefon unga ulanadi.
final class LanTransport: LinkTransport {
    static let defaultPort: UInt16 = 47123

    private unowned let engine: SyncEngine
    private var listener: NWListener?
    private var listenerReady = false
    private var links: [ObjectIdentifier: LanLink] = [:]
    private var pingTimer: Timer?
    private var restartWork: DispatchWorkItem?
    private(set) var isRunning = false
    private(set) var port: UInt16 = defaultPort

    init(engine: SyncEngine) { self.engine = engine }

    var connectedPeers: Set<String> { Set(links.values.compactMap(\.peerId)) }

    var statusText: String {
        guard isRunning else { return "O'chirilgan" }
        guard listenerReady else { return "Ishga tushmoqda…" }
        let n = connectedPeers.count
        return n > 0 ? "\(n) ta qurilma ulangan" : "Kutilmoqda"
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        openListener(port: Self.defaultPort)
        let t = Timer(timeInterval: 20, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        pingTimer = t
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        restartWork?.cancel()
        pingTimer?.invalidate()
        pingTimer = nil
        listener?.cancel()
        listener = nil
        listenerReady = false
        links.values.forEach { $0.close() }
        links.removeAll()
        engine.changed()
    }

    private func openListener(port requested: UInt16?) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true

        let l: NWListener
        do {
            if let requested, let p = NWEndpoint.Port(rawValue: requested) {
                l = try NWListener(using: params, on: p)
            } else {
                l = try NWListener(using: params)
            }
        } catch {
            if requested != nil { openListener(port: nil) } else { scheduleRestart() }
            return
        }

        l.service = NWListener.Service(name: "Nexo-\(engine.key.groupId)", type: "_nexo._tcp")
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self, let l, self.listener === l else { return }
            switch state {
            case .ready:
                self.listenerReady = true
                self.port = l.port?.rawValue ?? requested ?? 0
                self.engine.changed()
            case .failed(let error):
                self.listenerReady = false
                self.listener = nil
                l.cancel()
                // Port band bo'lsa — tizim tanlagan port bilan (Bonjour baribir to'g'ri portni e'lon qiladi)
                if case .posix(let code) = error, code == .EADDRINUSE, requested != nil {
                    self.openListener(port: nil)
                } else {
                    self.scheduleRestart()
                }
                self.engine.changed()
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
        listener = l
        l.start(queue: .main)
    }

    private func scheduleRestart() {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.isRunning, self.listener == nil else { return }
            self.openListener(port: Self.defaultPort)
        }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }

    private func accept(_ conn: NWConnection) {
        let link = LanLink(conn: conn)
        let id = ObjectIdentifier(link)
        links[id] = link
        link.onFrame = { [weak self, weak link] payload in
            guard let self, let link else { return }
            guard let msg = self.engine.receive(payload, via: .lan) else { return }
            if msg.t == "hello", link.peerId != msg.from {
                link.peerId = msg.from
                self.engine.changed()
                self.engine.peerLinked()
            }
        }
        link.onClose = { [weak self] in
            guard let self, self.links[id] != nil else { return }
            self.links[id] = nil
            self.engine.changed()
        }
        link.start()
        if let hello = engine.helloPayload() { link.send(hello) }
    }

    private func tick() {
        guard let ping = engine.pingPayload() else { return }
        let now = Date()
        for link in links.values {
            // Kalitni bilmaydigan yoki javob bermay qolgan ulanishlarni yopamiz
            if link.peerId == nil, now.timeIntervalSince(link.opened) > 15 { link.close(); continue }
            if now.timeIntervalSince(link.lastRx) > 70 { link.close(); continue }
            link.send(ping)
        }
    }

    func send(_ sealed: Data, to peers: Set<String>) {
        for link in links.values {
            if let peer = link.peerId, peers.contains(peer) { link.send(sealed) }
        }
    }
}

final class LanLink {
    let conn: NWConnection
    let opened = Date()
    var lastRx = Date()
    var peerId: String?
    var onFrame: ((Data) -> Void)?
    var onClose: (() -> Void)?
    private let parser = FrameParser()
    private var closed = false

    init(conn: NWConnection) { self.conn = conn }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        conn.start(queue: .main)
        receive()
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                // Katta fayl uzoq kelishi mumkin — har bir bo'lakda yangilaymiz, freym oxirida emas
                self.lastRx = Date()
                do {
                    for frame in try self.parser.push(data) { self.onFrame?(frame) }
                } catch {
                    self.close()
                    return
                }
            }
            if isComplete || error != nil { self.close() } else { self.receive() }
        }
    }

    func send(_ payload: Data) {
        guard !closed else { return }
        conn.send(content: Wire.frame(payload), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.close() }
        })
    }

    func close() {
        guard !closed else { return }
        closed = true
        conn.cancel()
        onClose?()
    }
}
