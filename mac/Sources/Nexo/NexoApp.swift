import SwiftUI
import AppKit
import ServiceManagement
import CoreImage.CIFilterBuiltins

@main
struct NexoApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuView().environmentObject(model)
        } label: {
            Image(systemName: model.engine.hasActiveLink || model.engine.internet.connected
                  ? "doc.on.clipboard.fill" : "doc.on.clipboard")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppModel: ObservableObject {
    let engine = SyncEngine()
    @Published private(set) var loginItemEnabled = false

    init() {
        engine.onChange = { [weak self] in self?.objectWillChange.send() }
        Notifier.shared.setup()
        engine.start()

        // Birinchi ishga tushishda avtomatik ravishda login item qilib qo'yamiz
        if !engine.settings.didSetupLoginItem {
            engine.settings.didSetupLoginItem = true
            setLoginItem(true)
        }
        loginItemEnabled = SMAppService.mainApp.status == .enabled

        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.engine.handleWake() }
    }

    func setLoginItem(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Nexo login item: \(error)")
        }
        loginItemEnabled = SMAppService.mainApp.status == .enabled
    }

    func binding(_ keyPath: ReferenceWritableKeyPath<Settings, Bool>) -> Binding<Bool> {
        Binding(
            get: { self.engine.settings[keyPath: keyPath] },
            set: {
                self.engine.settings[keyPath: keyPath] = $0
                self.engine.applySettings()
            }
        )
    }
}

struct MenuView: View {
    @EnvironmentObject var model: AppModel
    @State private var showPairing = false
    @State private var server = Settings.shared.relayServer

    var body: some View {
        clipboardTab
            .padding(14)
            .frame(width: 340)
    }

    @ViewBuilder
    private var clipboardTab: some View {
        let engine = model.engine
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Nexo").font(.headline)
                Spacer()
                Toggle("", isOn: model.binding(\.syncEnabled))
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .help("Sinxronlashni yoqish / to'xtatish")
            }

            VStack(spacing: 8) {
                TransportRow(icon: "wifi", title: "Wi-Fi", status: engine.lan.statusText,
                             active: !engine.lan.connectedPeers.isEmpty, enabled: model.binding(\.lanEnabled))
                TransportRow(icon: "dot.radiowaves.left.and.right", title: "Bluetooth", status: engine.ble.statusText,
                             active: !engine.ble.connectedPeers.isEmpty, enabled: model.binding(\.bleEnabled))
                TransportRow(icon: "globe", title: "Internet", status: engine.internet.statusText,
                             active: engine.internet.connected, enabled: model.binding(\.internetEnabled))
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.05)))

            if let event = engine.lastEvent {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: event.incoming ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                        .foregroundStyle(event.incoming ? .green : .blue)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.preview).lineLimit(2).font(.callout)
                        Text(eventCaption(event)).font(.caption).foregroundStyle(.secondary)
                    }
                    if let url = event.fileURL {
                        Spacer()
                        Button {
                            NSWorkspace.shared.activateFileViewerSelecting([url])
                        } label: {
                            Image(systemName: "magnifyingglass")
                        }
                        .buttonStyle(.borderless)
                        .help("Finder'da ko'rsatish")
                    }
                }
            }

            HStack {
                Toggle("Rasmlar ham", isOn: model.binding(\.imagesEnabled))
                    .help("Clipboard'dagi rasmlarni ham sinxronlash")
                Spacer()
                Button("Fayl yuborish…", action: pickFiles)
                    .disabled(!engine.settings.syncEnabled)
            }
            .controlSize(.small)

            ForEach(engine.settings.knownPeers.sorted(by: { $0.value < $1.value }), id: \.key) { id, name in
                HStack {
                    Label(name, systemImage: "iphone")
                    Spacer()
                    Button {
                        engine.forgetPeer(id)
                    } label: {
                        Image(systemName: "xmark.circle")
                    }
                    .buttonStyle(.borderless)
                    .help("Qurilmani unutish (u endi internet orqali kutilmaydi)")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Divider()

            DisclosureGroup("Telefonni ulash", isExpanded: $showPairing) {
                PairingView(engine: engine).padding(.top, 6)
            }

            Toggle("Mac yonganda avtomatik ishga tushsin", isOn: Binding(
                get: { model.loginItemEnabled },
                set: { model.setLoginItem($0) }
            ))

            HStack {
                TextField("Internet server (ntfy)", text: $server)
                    .textFieldStyle(.roundedBorder)
                    .font(.caption)
                    .onSubmit(saveServer)
                if server != engine.settings.relayServer {
                    Button("Saqlash", action: saveServer).controlSize(.small)
                }
            }

            HStack {
                Button("Yangi kalit…", action: confirmNewKey)
                Spacer()
                Button("Chiqish") { NSApp.terminate(nil) }
            }
            .controlSize(.small)
        }
    }

    private func eventCaption(_ e: SyncEvent) -> String {
        let time = e.date.formatted(date: .omitted, time: .shortened)
        let direction = e.incoming ? "\(e.peerName) → Mac" : "Mac → \(e.peerName.isEmpty ? "?" : e.peerName)"
        let channel = e.channel?.rawValue ?? "navbatda"
        return "\(direction) · \(channel) · \(time)"
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.message = "Telefonga yuboriladigan fayllar (har biri \(sizeLabel(SyncEngine.maxFileBytes)) gacha)"
        panel.prompt = "Yuborish"
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        let skipped = model.engine.sendFiles(panel.urls)
        guard !skipped.isEmpty else { return }
        let alert = NSAlert()
        alert.messageText = "Ba'zi fayllar yuborilmadi"
        alert.informativeText = "\(skipped.joined(separator: ", "))\n\nFayl \(sizeLabel(SyncEngine.maxFileBytes)) dan katta yoki o'qib bo'lmadi."
        alert.runModal()
    }

    private func saveServer() {
        let trimmed = server.trimmingCharacters(in: .whitespacesAndNewlines)
        guard URL(string: trimmed)?.scheme?.hasPrefix("http") == true else { return }
        model.engine.settings.relayServer = trimmed
        model.engine.restartInternet()
    }

    private func confirmNewKey() {
        let alert = NSAlert()
        alert.messageText = "Yangi kalit yaratilsinmi?"
        alert.informativeText = "Barcha ulangan telefonlar uzilib qoladi, ularni QR kod orqali qayta ulash kerak bo'ladi."
        alert.addButton(withTitle: "Yaratish")
        alert.addButton(withTitle: "Bekor qilish")
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertFirstButtonReturn {
            model.engine.regenerateKey()
            showPairing = true
        }
    }
}

struct TransportRow: View {
    let icon: String
    let title: String
    let status: String
    let active: Bool
    @Binding var enabled: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .frame(width: 18)
                .foregroundStyle(active ? Color.green : Color.secondary)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.callout)
                Text(status).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: $enabled).toggleStyle(.switch).controlSize(.mini).labelsHidden()
        }
    }
}

struct PairingView: View {
    let engine: SyncEngine

    var body: some View {
        let uri = engine.pairingURI
        VStack(spacing: 8) {
            if let image = qrImage(uri) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 190, height: 190)
                    .padding(8)
                    .background(Color.white)
                    .cornerRadius(6)
            }
            Text("Android'da Nexo → Sozlamalar → Clipboard → «QR skanerlash»")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Kodni nusxalash") {
                // write() orqali — bu matn boshqa qurilmalarga yuborilmaydi
                engine.clipboard.write(uri)
            }
            .controlSize(.small)
        }
        .frame(maxWidth: .infinity)
    }

    private func qrImage(_ string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
