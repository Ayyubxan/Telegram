import AppKit
import Foundation

/// "Yordamchi parki" — robot telefonlar holati va hisobot sahifasi
/// (`robot_dialer/tools/fleet/index.html`). Kalit shu Mac'niki (Nexo guruh kaliti):
/// sahifaga `#k=` bilan uzatiladi, sahifa uni o'zida saqlab URL'dan o'chiradi.
enum FleetPage {
    static let page = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Code/robot_dialer/tools/fleet/index.html")

    static var available: Bool { FileManager.default.fileExists(atPath: page.path) }

    /// LaunchServices file:// URL'dan `#…` ni tashlab yuboradi — shuning uchun oraliq
    /// sahifa (Application Support'da, faqat shu foydalanuvchiga) JS bilan yo'naltiradi.
    static func open(key: String) {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Nexo", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let launcher = dir.appendingPathComponent("fleet.html")
        let target = page.absoluteString + "#k=" + key
        let html = "<!doctype html><meta charset=utf-8><script>location.replace(\(jsString(target)))</script>"
        do {
            try html.write(to: launcher, atomically: true, encoding: .utf8)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: launcher.path)
            NSWorkspace.shared.open(launcher)
        } catch {
            NSWorkspace.shared.open(page)
        }
    }

    private static func jsString(_ s: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [s])
        let arr = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(arr.dropFirst().dropLast())
    }
}
