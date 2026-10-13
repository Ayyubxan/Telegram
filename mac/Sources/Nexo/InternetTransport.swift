import Foundation
import Network

/// Internet: ntfy (https://ntfy.sh yoki o'zingizning serveringiz) pub/sub orqali.
/// Server faqat shifrlangan baytlarni ko'radi; topic nomi kalitdan hosil qilinadi.
final class InternetTransport: NSObject, URLSessionDataDelegate {
    /// ntfy xabar limiti 4096 bayt — kattaroq clip'lar attachment sifatida yuboriladi
    private static let inlineLimit = 3800

    private unowned let engine: SyncEngine
    private var session: URLSession!
    private var stream: URLSessionDataTask?
    private var buffer = Data()
    private var backoff: TimeInterval = 1
    private var retryWork: DispatchWorkItem?
    private var monitor: NWPathMonitor?
    private var sawFirstPath = false
    private var pending: Data?
    private(set) var isRunning = false
    private(set) var connected = false

    init(engine: SyncEngine) {
        self.engine = engine
        super.init()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120  // ntfy har ~45 soniyada keepalive yuboradi
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }

    var statusText: String {
        guard isRunning else { return "O'chirilgan" }
        return connected ? "Ulangan" : "Ulanmoqda…"
    }

    private var baseURL: URL? {
        var s = engine.settings.relayServer.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return URL(string: s)
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        backoff = 1
        sawFirstPath = false
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            // Tarmoq almashganda eski ulanish "osilib" qolmasligi uchun darhol qayta ulanamiz
            if self.sawFirstPath, path.status == .satisfied { self.reconnectNow() }
            self.sawFirstPath = true
        }
        m.start(queue: .main)
        monitor = m
        connect()
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        retryWork?.cancel()
        monitor?.cancel()
        monitor = nil
        stream?.cancel()
        stream = nil
        connected = false
        engine.changed()
    }

    func reconnectNow() {
        guard isRunning else { return }
        retryWork?.cancel()
        backoff = 1
        stream?.cancel()
        stream = nil
        if connected {
            connected = false
            engine.changed()
        }
        connect()
    }

    private func connect() {
        guard isRunning, stream == nil, let base = baseURL,
              var comps = URLComponents(url: base.appendingPathComponent(engine.key.topic).appendingPathComponent("json"),
                                        resolvingAgainstBaseURL: false) else { return }
        if let since = engine.settings.relayLastId(topic: engine.key.topic) {
            comps.queryItems = [URLQueryItem(name: "since", value: since)]
        }
        guard let url = comps.url else { return }
        buffer.removeAll()
        let task = session.dataTask(with: URLRequest(url: url))
        stream = task
        task.resume()
    }

    private func scheduleRetry() {
        guard isRunning else { return }
        retryWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.connect() }
        retryWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + backoff, execute: work)
        backoff = min(backoff * 2, 60)
    }

    // MARK: URLSessionDataDelegate

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard dataTask === stream, (response as? HTTPURLResponse)?.statusCode == 200 else {
            completionHandler(.cancel)
            return
        }
        connected = true
        backoff = 1
        engine.changed()
        engine.internetLinked()
        completionHandler(.allow)
        if let p = pending {
            pending = nil
            publish(p)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask === stream else { return }
        buffer.append(data)
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = buffer.subdata(in: buffer.startIndex ..< nl)
            buffer.removeSubrange(buffer.startIndex ... nl)
            handleLine(line)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === stream else { return }
        stream = nil
        if connected {
            connected = false
            engine.changed()
        }
        scheduleRetry()
    }

    private func handleLine(_ line: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
              obj["event"] as? String == "message" else { return }
        if let id = obj["id"] as? String {
            engine.settings.setRelayLastId(id, topic: engine.key.topic)
        }
        if let attachment = obj["attachment"] as? [String: Any],
           let urlString = attachment["url"] as? String, let url = URL(string: urlString) {
            URLSession.shared.dataTask(with: url) { [weak self] data, response, _ in
                guard let data, (response as? HTTPURLResponse)?.statusCode == 200 else { return }
                DispatchQueue.main.async { self?.engine.receive(data, via: .internet) }
            }.resume()
        } else if let text = obj["message"] as? String, let sealed = Data(base64Encoded: text) {
            engine.receive(sealed, via: .internet)
        }
    }

    // MARK: Publish

    func publish(_ sealed: Data) {
        guard let base = baseURL else { return }
        var request = URLRequest(url: base.appendingPathComponent(engine.key.topic))
        // Katta fayllar sekin internetda ham yuklanib ulgurishi uchun (~50 KB/s hisobida)
        request.timeoutInterval = 30 + Double(sealed.count) / 50_000
        let b64 = sealed.base64EncodedString()
        if b64.utf8.count <= Self.inlineLimit {
            request.httpMethod = "POST"
            request.httpBody = Data(b64.utf8)
        } else {
            request.httpMethod = "PUT"
            request.httpBody = sealed
            request.setValue("clip.bin", forHTTPHeaderField: "Filename")
        }
        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            let ok = error == nil && (200 ..< 300).contains((response as? HTTPURLResponse)?.statusCode ?? 0)
            guard !ok else { return }
            // Faqat eng oxirgi yuborilmagan clip'ni saqlaymiz — ulanish tiklanganda yuboriladi
            DispatchQueue.main.async { self?.pending = sealed }
        }.resume()
    }
}
