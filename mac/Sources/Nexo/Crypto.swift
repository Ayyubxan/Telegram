import Foundation
import CryptoKit

/// Guruh kaliti: Mac va barcha ulangan telefonlar bitta 32 baytlik kalitni bo'lishadi.
/// Undan shifrlash kaliti, Bonjour nomi, BLE servis UUID va internet topic hosil qilinadi.
struct GroupKey {
    let raw: Data

    init?(raw: Data) {
        guard raw.count == 32 else { return nil }
        self.raw = raw
    }

    static func generate() -> GroupKey {
        GroupKey(raw: SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) })!
    }

    init?(base64url: String) {
        var s = base64url.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        guard let data = Data(base64Encoded: s) else { return nil }
        self.init(raw: data)
    }

    var base64url: String {
        raw.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func derive(_ label: String) -> Data {
        var h = SHA256()
        h.update(data: Data(label.utf8))
        h.update(data: raw)
        return Data(h.finalize())
    }

    var groupId: String { String(derive("nexo-group").hex.prefix(16)) }

    var topic: String { "nexo_" + derive("nexo-topic").hex.prefix(32) }

    /// Hash'ning birinchi 16 baytidan olingan UUID — boshqa odamlarning Nexo'lari bilan aralashmaydi.
    var bleServiceUUID: String {
        let h = derive("nexo-ble").prefix(16).hex.uppercased()
        let c = Array(h)
        return "\(String(c[0..<8]))-\(String(c[8..<12]))-\(String(c[12..<16]))-\(String(c[16..<20]))-\(String(c[20..<32]))"
    }

    /// nonce(12) + ciphertext + tag(16)
    func seal(_ plaintext: Data) -> Data? {
        try? AES.GCM.seal(plaintext, using: SymmetricKey(data: raw)).combined
    }

    func open(_ sealed: Data) -> Data? {
        guard sealed.count >= 28, let box = try? AES.GCM.SealedBox(combined: sealed) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: raw))
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
