import Foundation

/// Fragmentation matching the Android side (docs/PROTOCOL.md).
/// Header: [marker:1][sequence:8][index:2] + data. 11 bytes total.
enum Fragmentation {
    static let headerSize = 11
    static let maxFragmentTotal = 500

    static let markerSingle: UInt8 = 0x00
    static let markerContinue: UInt8 = 0x01
    static let markerLast: UInt8 = 0x02

    /// Split a message into fragments with a shared sequence.
    static func split(sequence: UInt64, message: Data) -> [Data] {
        let payloadMax = maxFragmentTotal - headerSize
        if message.count <= payloadMax {
            return [build(marker: markerSingle, sequence: sequence, index: 0, chunk: message)]
        }
        var result: [Data] = []
        var offset = 0
        var index = 0
        while offset < message.count {
            let isLast = offset + payloadMax >= message.count
            let marker = isLast ? markerLast : markerContinue
            let end = min(offset + payloadMax, message.count)
            let chunk = message.subdata(in: offset..<end)
            result.append(build(marker: marker, sequence: sequence, index: index, chunk: chunk))
            offset += chunk.count
            index += 1
        }
        return result
    }

    private static func build(marker: UInt8, sequence: UInt64, index: Int, chunk: Data) -> Data {
        var out = Data(capacity: headerSize + chunk.count)
        out.append(marker)
        for shift in stride(from: 56, through: 0, by: -8) {
            out.append(UInt8((sequence >> UInt64(shift)) & 0xFF))
        }
        out.append(UInt8((index >> 8) & 0xFF))
        out.append(UInt8(index & 0xFF))
        out.append(chunk)
        return out
    }

    /// Assemble a fragment into a full message once complete.
    /// Returns nil until the last fragment is received.
    static func accept(fragment: Data, pending: inout [UInt64: [Data]]) -> Data? {
        guard fragment.count >= headerSize else { return nil }
        let marker = fragment[fragment.startIndex]
        let sequence = bytesToUInt64(Array(fragment[fragment.startIndex+1..<fragment.startIndex+9]))
        let index = (Int(fragment[fragment.startIndex+9]) << 8) | Int(fragment[fragment.startIndex+10])
        let data = fragment.subdata(in: fragment.startIndex+headerSize..<fragment.endIndex)

        switch marker {
        case markerSingle:
            return data
        case markerContinue, markerLast:
            var list = pending[sequence] ?? []
            while list.count <= index { list.append(Data()) }
            list[index] = data
            if marker == markerLast {
                pending[sequence] = nil
                let total = list.reduce(0) { $0 + $1.count }
                var out = Data(capacity: total)
                for part in list { out.append(part) }
                return out
            } else {
                pending[sequence] = list
                return nil
            }
        default:
            return nil
        }
    }

    private static func bytesToUInt64(_ bytes: [UInt8]) -> UInt64 {
        var value: UInt64 = 0
        for byte in bytes {
            value = (value << 8) | UInt64(byte)
        }
        return value
    }
}
