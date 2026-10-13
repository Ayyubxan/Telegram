import Foundation

/// Shifrlanadigan xabar. Android tomonida ham xuddi shu JSON maydonlari ishlatiladi.
struct Message: Codable {
    var t: String          // "hello" | "clip" | "file" | "ping"
    var from: String       // qurilma ID
    var name: String?
    var os: String?
    var mid: String?       // clip ID (dublikatlarni tashlab yuborish uchun)
    var ts: Int64?         // ms, unix
    var text: String?
    var file: String?      // fayl nomi
    var mime: String?
    var data: Data?        // rasm ("clip") yoki fayl ("file") baytlari, JSON'da base64
}

enum Channel: String {
    case lan = "Wi-Fi"
    case ble = "Bluetooth"
    case internet = "Internet"
}

/// Oqimli kanallar (TCP, BLE) uchun freym: 4 bayt uzunlik (big-endian) + shifrlangan payload.
enum Wire {
    static let maxFrame = 16 * 1024 * 1024

    static func frame(_ payload: Data) -> Data {
        var len = UInt32(payload.count).bigEndian
        var out = Data(withUnsafeBytes(of: &len) { Array($0) })
        out.append(payload)
        return out
    }
}

struct FrameError: Error {}

final class FrameParser {
    private var buffer = Data()

    func push(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames: [Data] = []
        while buffer.count >= 4 {
            let b = [UInt8](buffer.prefix(4))
            let len = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard len > 0, len <= Wire.maxFrame else { throw FrameError() }
            guard buffer.count >= 4 + len else { break }
            let start = buffer.startIndex
            frames.append(buffer.subdata(in: start + 4 ..< start + 4 + len))
            buffer = buffer.subdata(in: start + 4 + len ..< buffer.endIndex)
        }
        return frames
    }
}
