import Foundation
import CoreGraphics

/// Минимальный генератор QR-кода (версия 1-M / 1-L, байтовый режим), без CoreImage.
/// Достаточно для нашего payload "WRISTRELAY:1:<hex nonce>" (~80 символов) —
/// это укладывается в версию 4-L (78 байт) / 5-L (106 байт).
///
/// Алгоритм: стандартный QR по ISO/IEC 18004 (байтовый режим, маска 0,
/// Reed-Solomon коррекция ошибок уровня L).
enum QrCodeGenerator {

    // --- Таблицы GF(256) для Reed-Solomon ---
    private static let expTable: [Int] = {
        var exp = [Int](repeating: 0, count: 512)
        var log = [Int](repeating: 0, count: 256)
        var x = 1
        for i in 0..<255 {
            exp[i] = x
            log[x] = i
            x <<= 1
            if x & 0x100 != 0 { x ^= 0x11D }
        }
        for i in 255..<512 { exp[i] = exp[i - 255] }
        return exp
    }()

    private static let logTable: [Int] = {
        var log = [Int](repeating: 0, count: 256)
        var x = 1
        for i in 0..<255 {
            log[x] = i
            x <<= 1
            if x & 0x100 != 0 { x ^= 0x11D }
        }
        return log
    }()

    private static func gfMul(_ a: Int, _ b: Int) -> Int {
        if a == 0 || b == 0 { return 0 }
        return expTable[logTable[a] + logTable[b]]
    }

    /// Параметры: version -> (кодовых слов данных, кодовых слов EC на блок)
    /// Для L-уровня. (из ISO таблицы)
    private static let versionParams: [Int: (Int, Int)] = [
        1: (19, 7),
        2: (34, 10),
        3: (55, 15),
        4: (80, 20),
        5: (108, 26),
        6: (136, 18),
        7: (156, 20),
        8: (194, 24)
    ]

    /// Маски, фиксированные позиции и т.д. — генератор байтового QR.
    static func generate(data: String, correctionLevel: Character = "L") -> [[Bool]] {
        let bytes = Array(data.utf8)
        // Подбираем версию под длину (байтовый режим, L).
        var version = 1
        while version <= 8 {
            let capacity = byteCapacity(version: version, level: correctionLevel)
            if bytes.count <= capacity { break }
            version += 1
        }
        if version > 8 {
            // Слишком длинно — обрезаем до 8-L.
            version = 8
        }

        let size = version * 4 + 17
        var modules = [[Bool]](repeating: [Bool](repeating: false, count: size), count: size)

        placeFunctionPatterns(&modules, version: version)
        placeData(modules: &modules, version: version, bytes: bytes)
        placeFormatInfo(&modules, version: version, level: correctionLevel)

        return modules
    }

    private static func byteCapacity(version: Int, level: Character) -> Int {
        let (dataCodewords, _) = versionParams[version] ?? (19, 7)
        // Байтовый режим: 4 бита режим + 8 бит длина (для v1-9) = 12 бит заголовка
        let headerBits = 12
        let dataBits = dataCodewords * 8
        return (dataBits - headerBits) / 8
    }

    private static func placeFunctionPatterns(_ modules: inout [[Bool]], version: Int) {
        let size = modules.count
        // Finder patterns в трёх углах
        placeFinder(&modules, row: 0, col: 0)
        placeFinder(&modules, row: size - 7, col: 0)
        placeFinder(&modules, row: 0, col: size - 7)

        // Separators
        for i in 0..<8 {
            if i < size {
                set(&modules, size - 8, i, false)
                set(&modules, i, size - 8, false)
            }
            set(&modules, 7, i + 1, false)
            set(&modules, i + 1, 7, false)
        }

        // Timing patterns
        for i in 8..<(size - 8) {
            set(&modules, 6, i, i % 2 == 0)
            set(&modules, i, 6, i % 2 == 0)
        }

        // Dark module
        set(&modules, size - 8, 8, true)
    }

    private static func placeFinder(_ modules: inout [[Bool]], row: Int, col: Int) {
        for r in 0..<7 {
            for c in 0..<7 {
                let isBorder = r == 0 || r == 6 || c == 0 || c == 6
                let isCenter = r >= 2 && r <= 4 && c >= 2 && c <= 4
                set(&modules, row + r, col + c, isBorder || isCenter)
            }
        }
    }

    private static func set(_ modules: inout [[Bool]], _ row: Int, _ col: Int, _ value: Bool) {
        if row >= 0 && row < modules.count && col >= 0 && col < modules.count {
            modules[row][col] = value
        }
    }

    /// Кодирование байтового режима + Reed-Solomon.
    private static func buildCodewords(_ bytes: [UInt8], version: Int, level: Character) -> [Int] {
        let (dataCapacity, ecCodewords) = versionParams[version] ?? (19, 7)

        // Заголовок: режим 0100 (байты), длина (8 бит для v1-9)
        var bitStream: [Bool] = []
        appendBits(0b0100, count: 4, to: &bitStream)
        appendBits(bytes.count, count: 8, to: &bitStream)
        for byte in bytes {
            appendBits(Int(byte), count: 8, to: &bitStream)
        }
        // Терминатор
        let remaining = dataCapacity * 8 - bitStream.count
        if remaining > 0 {
            appendBits(0, count: min(remaining, 4), to: &bitStream)
        }
        // Выравнивание до байта
        while bitStream.count % 8 != 0 { bitStream.append(false) }
        // Паддинг байты 0xEC 0x11
        let dataBytes = (bitStream.count / 8)
        var codewords = (0..<dataBytes).map { i -> Int in
            var value = 0
            for b in 0..<8 {
                value = (value << 1) | (bitStream[i * 8 + b] ? 1 : 0)
            }
            return value
        }
        var padByte = 0xEC
        while codewords.count < dataCapacity {
            codewords.append(padByte)
            padByte = (padByte == 0xEC) ? 0x11 : 0xEC
        }

        // Reed-Solomon
        let ec = reedSolomon(codewords, degree: ecCodewords)
        return codewords + ec
    }

    private static func appendBits(_ value: Int, count: Int, to bits: inout [Bool]) {
        for i in stride(from: count - 1, through: 0, by: -1) {
            bits.append(((value >> i) & 1) == 1)
        }
    }

    private static func reedSolomon(_ data: [Int], degree: Int) -> [Int] {
        var gen = [1]
        for i in 0..<degree {
            let next = [Int](repeating: 0, count: gen.count + 1)
            for (j, g) in gen.enumerated() {
                next[j] ^= gfMul(g, expTable[i])
                next[j + 1] ^= g
            }
            gen = next
        }
        var rem = [Int](repeating: 0, count: degree)
        for d in data {
            let factor = d ^ rem[0]
            rem.removeFirst()
            rem.append(0)
            for (j, g) in gen.enumerated() where g != 0 {
                if j < rem.count {
                    rem[j] ^= gfMul(g, factor)
                }
            }
        }
        return rem
    }

    private static func placeData(modules: inout [[Bool]], version: Int, bytes: [UInt8]) {
        let size = modules.count
        let all = buildCodewords(bytes, version: version, level: "L")

        var bitIndex = 0
        var upward = true
        var col = size - 1
        while col > 0 {
            if col == 6 { col -= 1 }
            var row = upward ? size - 1 : 0
            while row >= 0 && row < size {
                for _ in 0..<2 {
                    if !isFunction(modules, row, col) {
                        let bit = bitIndex < all.count * 8
                            ? ((all[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1) == 1
                            : false
                        set(&modules, row, col, bit)
                        bitIndex += 1
                    }
                    col -= 1
                }
                row += upward ? -1 : 1
            }
            upward.toggle()
            col -= 1
        }
    }

    private static func isFunction(_ modules: [[Bool]], _ row: Int, _ col: Int) -> Bool {
        let size = modules.count
        if row == 6 || col == 6 { return true }
        if row < 9 && col < 9 { return true }
        if row < 9 && col >= size - 8 { return true }
        if col < 9 && row >= size - 8 { return true }
        return false
    }

    private static func placeFormatInfo(_ modules: inout [[Bool]], version: Int, level: Character) {
        let size = modules.count
        let levelBits: Int
        switch level {
        case "L": levelBits = 0b01
        case "M": levelBits = 0b00
        case "Q": levelBits = 0b11
        default: levelBits = 0b10
        }
        // маска 0 (выбрали фиксированную)
        let data = (levelBits << 3) | 0
        var format = data << 10
        let g = 0x537
        var rem = format
        for _ in 0..<15 {
            if rem & (1 << 14) != 0 {
                rem ^= g
            }
            rem <<= 1
        }
        format = ((data << 10) | rem) & 0x7FFF
        // BCH: XOR с маской 101010000010010
        format ^= 0b101010000010010

        // Записываем format info (упрощённо, обе копии)
        for i in 0..<15 {
            let bit = ((format >> i) & 1) == 1
            // верхняя левая
            if i < 6 {
                set(&modules, i, 8, bit)
            } else if i < 8 {
                set(&modules, i + 1, 8, bit)
            } else {
                set(&modules, size - 15 + i, 8, bit)
            }
            // вторая копия (низ-лево, право-верх)
            if i < 8 {
                set(&modules, 8, size - i - 1, bit)
            } else {
                set(&modules, 8, size - 15 + i - 7, bit)
            }
        }
        set(&modules, 8, size - 8, true)
    }

    /// Возвращает CGImage с QR (для отображения в SwiftUI Image).
    static func cgImage(data: String, size: Int) -> CGImage? {
        let modules = generate(data: data, correctionLevel: "L")
        let n = modules.count
        let scale = max(1, size / n)
        let dim = n * scale

        var pixels = [UInt8](repeating: 255, count: dim * dim * 4) // white opaque
        for r in 0..<n {
            for c in 0..<n {
                if modules[r][c] {
                    for pr in 0..<scale {
                        for pc in 0..<scale {
                            let x = c * scale + pc
                            let y = r * scale + pr
                            let idx = (y * dim + x) * 4
                            pixels[idx] = 0
                            pixels[idx + 1] = 0
                            pixels[idx + 2] = 0
                            pixels[idx + 3] = 255
                        }
                    }
                }
            }
        }

        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        return CGImage(
            width: dim,
            height: dim,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: dim * 4,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        )
    }
}
