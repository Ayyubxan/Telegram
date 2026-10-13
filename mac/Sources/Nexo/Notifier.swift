import AppKit
import UserNotifications

/// Fayl qabul qilinganda macOS bildirishnomasi; bosilsa Finder'da ko'rsatadi.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    // .app bundle'siz (`swift run`) UNUserNotificationCenter ishlamaydi
    private var center: UNUserNotificationCenter? {
        Bundle.main.bundleIdentifier == nil ? nil : .current()
    }

    func setup() {
        center?.delegate = self
    }

    func fileReceived(_ url: URL, from peer: String) {
        guard let center else { return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "Fayl qabul qilindi"
            content.body = "\(url.lastPathComponent) — \(peer)"
            content.sound = .default
            content.userInfo = ["path": url.path]
            center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        }
    }

    // Menu bar ilovasi ham ko'rsatsin
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let path = response.notification.request.content.userInfo["path"] as? String {
            DispatchQueue.main.async {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            }
        }
        completionHandler()
    }
}
