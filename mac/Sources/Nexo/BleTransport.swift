import Foundation
import CoreBluetooth

/// Bluetooth LE: Mac periferik (GATT server), telefon markaziy qurilma sifatida ulanadi.
/// RX — telefon yozadi, TX — Mac notify yuboradi. Freymlar MTU bo'yicha bo'laklarga bo'linadi.
final class BleTransport: NSObject, LinkTransport, CBPeripheralManagerDelegate {
    static let rxUUID = CBUUID(string: "5C1B0001-6A3E-4F3B-9D2A-C11B5EC0FFEE")
    static let txUUID = CBUUID(string: "5C1B0002-6A3E-4F3B-9D2A-C11B5EC0FFEE")

    private unowned let engine: SyncEngine
    private var manager: CBPeripheralManager?
    private var txChar: CBMutableCharacteristic?
    private var links: [UUID: BleLink] = [:]
    private var pingTimer: Timer?
    private(set) var isRunning = false

    init(engine: SyncEngine) { self.engine = engine }

    var connectedPeers: Set<String> { Set(links.values.compactMap(\.peerId)) }

    var statusText: String {
        guard isRunning, let m = manager else { return "O'chirilgan" }
        switch m.state {
        case .poweredOn:
            let n = connectedPeers.count
            if n > 0 { return "\(n) ta qurilma ulangan" }
            return m.isAdvertising ? "Kutilmoqda" : "Ishga tushmoqda…"
        case .poweredOff: return "Bluetooth o'chiq"
        case .unauthorized: return "Ruxsat berilmagan"
        case .unsupported: return "Qo'llab-quvvatlanmaydi"
        default: return "Ishga tushmoqda…"
        }
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        manager = CBPeripheralManager(delegate: self, queue: .main)
        let t = Timer(timeInterval: 30, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        pingTimer = t
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        pingTimer?.invalidate()
        pingTimer = nil
        if let m = manager {
            m.stopAdvertising()
            m.removeAllServices()
            m.delegate = nil
        }
        manager = nil
        txChar = nil
        links.removeAll()
        engine.changed()
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            publishService(peripheral)
        } else {
            links.removeAll()
        }
        engine.changed()
    }

    private func publishService(_ p: CBPeripheralManager) {
        p.stopAdvertising()
        p.removeAllServices()
        let rx = CBMutableCharacteristic(type: Self.rxUUID, properties: [.write], value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: Self.txUUID, properties: [.notify], value: nil, permissions: [.readable])
        let service = CBMutableService(type: CBUUID(string: engine.key.bleServiceUUID), primary: true)
        service.characteristics = [rx, tx]
        txChar = tx
        p.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            engine.changed()
            return
        }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [service.uuid]])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        engine.changed()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.txUUID else { return }
        let link = BleLink(central: central)
        links[central.identifier] = link
        if let hello = engine.helloPayload() { enqueue(hello, to: link) }
        engine.changed()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.txUUID else { return }
        links[central.identifier] = nil
        engine.changed()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == Self.rxUUID {
            guard let value = request.value, let link = links[request.central.identifier] else { continue }
            link.lastRx = Date()
            do {
                for frame in try link.parser.push(value) {
                    guard let msg = engine.receive(frame, via: .ble) else { continue }
                    if msg.t == "hello", link.peerId != msg.from {
                        link.peerId = msg.from
                        engine.changed()
                        engine.peerLinked()
                    }
                }
            } catch {
                link.parser = FrameParser()
            }
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        pump()
    }

    func send(_ sealed: Data, to peers: Set<String>) {
        for link in links.values {
            if let peer = link.peerId, peers.contains(peer) { enqueue(sealed, to: link) }
        }
    }

    private func enqueue(_ payload: Data, to link: BleLink) {
        let data = Wire.frame(payload)
        let size = max(20, link.central.maximumUpdateValueLength)
        var offset = 0
        while offset < data.count {
            let end = min(offset + size, data.count)
            link.queue.append(data.subdata(in: offset ..< end))
            offset = end
        }
        pump()
    }

    private func pump() {
        guard let m = manager, let tx = txChar else { return }
        for link in links.values {
            while let chunk = link.queue.first {
                // false — navbat to'lgan, peripheralManagerIsReady chaqirilganda davom etamiz
                guard m.updateValue(chunk, for: tx, onSubscribedCentrals: [link.central]) else { return }
                link.queue.removeFirst()
            }
        }
    }

    private func tick() {
        guard let ping = engine.pingPayload() else { return }
        let now = Date()
        var removed = false
        for (id, link) in links {
            if now.timeIntervalSince(link.lastRx) > 100 {
                links[id] = nil
                removed = true
                continue
            }
            enqueue(ping, to: link)
        }
        if removed { engine.changed() }
    }
}

final class BleLink {
    let central: CBCentral
    var parser = FrameParser()
    var peerId: String?
    var queue: [Data] = []
    var lastRx = Date()

    init(central: CBCentral) { self.central = central }
}
