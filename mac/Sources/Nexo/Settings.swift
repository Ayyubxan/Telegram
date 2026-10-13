import Foundation

final class Settings {
    static let shared = Settings()
    private let d = UserDefaults.standard

    let deviceId: String

    private init() {
        if let id = d.string(forKey: "deviceId") {
            deviceId = id
        } else {
            deviceId = UUID().uuidString.lowercased()
            d.set(deviceId, forKey: "deviceId")
        }
    }

    var groupKey: GroupKey {
        if let s = d.string(forKey: "groupKey"), let k = GroupKey(base64url: s) { return k }
        let k = GroupKey.generate()
        d.set(k.base64url, forKey: "groupKey")
        return k
    }

    func resetGroupKey() -> GroupKey {
        let k = GroupKey.generate()
        d.set(k.base64url, forKey: "groupKey")
        knownPeers = [:]
        return k
    }

    private func bool(_ key: String, default def: Bool) -> Bool {
        d.object(forKey: key) == nil ? def : d.bool(forKey: key)
    }

    var syncEnabled: Bool {
        get { bool("syncEnabled", default: true) }
        set { d.set(newValue, forKey: "syncEnabled") }
    }
    var lanEnabled: Bool {
        get { bool("lanEnabled", default: true) }
        set { d.set(newValue, forKey: "lanEnabled") }
    }
    var bleEnabled: Bool {
        get { bool("bleEnabled", default: true) }
        set { d.set(newValue, forKey: "bleEnabled") }
    }
    var internetEnabled: Bool {
        get { bool("internetEnabled", default: true) }
        set { d.set(newValue, forKey: "internetEnabled") }
    }
    /// Clipboard'dagi rasmlarni ham sinxronlash
    var imagesEnabled: Bool {
        get { bool("imagesEnabled", default: true) }
        set { d.set(newValue, forKey: "imagesEnabled") }
    }
    var didSetupLoginItem: Bool {
        get { d.bool(forKey: "didSetupLoginItem") }
        set { d.set(newValue, forKey: "didSetupLoginItem") }
    }

    var relayServer: String {
        get { d.string(forKey: "relayServer") ?? "https://ntfy.sh" }
        set { d.set(newValue, forKey: "relayServer") }
    }

    func relayLastId(topic: String) -> String? { d.string(forKey: "relayLastId.\(topic)") }
    func setRelayLastId(_ id: String, topic: String) { d.set(id, forKey: "relayLastId.\(topic)") }

    /// Qurilma ID → nomi
    var knownPeers: [String: String] {
        get { d.dictionary(forKey: "knownPeers") as? [String: String] ?? [:] }
        set { d.set(newValue, forKey: "knownPeers") }
    }
}
