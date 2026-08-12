import Foundation
import HealthKit

/// Чтение HealthKit на часах и отправка сэмплов на телефон.
/// HealthKit на watchOS доступен: шаги, ЧСС, активность.
final class HealthReader: NSObject, ObservableObject {

    enum AuthState {
        case unknown
        case denied
        case authorized
    }

    @Published private(set) var authState: AuthState = .unknown

    private let store = HKHealthStore()
    private let sendSample: (WB_HealthMetric, Double, Date) -> Void
    private var timer: Timer?

    /// Типы, которые читаем с часов.
    private var readTypes: Set<HKObjectType> {
        var types: Set<HKObjectType> = [
            HKObjectType.quantityType(forIdentifier: .stepCount)!,
            HKObjectType.quantityType(forIdentifier: .heartRate)!,
            HKObjectType.quantityType(forIdentifier: .activeEnergyBurned)!,
            HKObjectType.quantityType(forIdentifier: .distanceWalkingRunning)!,
            HKObjectType.quantityType(forIdentifier: .oxygenSaturation)!,
            HKObjectType.quantityType(forIdentifier: .restingHeartRate)!
        ]
        return types
    }

    init(sendSample: @escaping (WB_HealthMetric, Double, Date) -> Void) {
        self.sendSample = sendSample
        super.init()
    }

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Запрос разрешений HealthKit (показывается системным диалогом).
    func requestAuthorization() {
        guard isAvailable else { return }
        store.requestAuthorization(toShare: [], read: readTypes) { [weak self] success, _ in
            DispatchQueue.main.async {
                self?.authState = success ? .authorized : .denied
            }
        }
    }

    /// Запуск периодической отправки текущих значений.
    func startSending() {
        guard authState == .authorized else { return }
        sendSteps()
        sendHeartRate()
        // каждые 60 секунд — повтор
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.sendSteps()
            self?.sendHeartRate()
        }
    }

    func stopSending() {
        timer?.invalidate()
        timer = nil
    }

    private func sendSteps() {
        querySum(identifier: .stepCount) { value in
            self.sendSample(.steps, value, Date())
        }
    }

    private func sendHeartRate() {
        queryLatest(identifier: .heartRate) { bpm in
            self.sendSample(.heartRate, bpm, Date())
        }
    }

    /// Сумма значения за последние сутки (шаги, дистанция).
    private func querySum(identifier: HKQuantityTypeIdentifier, completion: @escaping (Double) -> Void) {
        guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else { return }
        let predicate = HKQuery.predicateForSamples(
            withStart: Calendar.current.startOfDay(for: Date()),
            end: Date(),
            options: .strictStartDate
        )
        let query = HKStatisticsQuery(
            quantityType: type,
            quantitySamplePredicate: predicate,
            options: .cumulativeSum
        ) { _, stats, _ in
            let value = stats?.sumQuantity()?.doubleValue(for: .count()) ?? 0
            DispatchQueue.main.async { completion(value) }
        }
        store.execute(query)
    }

    /// Последнее измерение (ЧСС).
    private func queryLatest(identifier: HKQuantityTypeIdentifier, completion: @escaping (Double) -> Void) {
        guard let type = HKQuantityType.quantityType(forIdentifier: identifier) else { return }
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)
        let query = HKSampleQuery(
            sampleType: type,
            predicate: nil,
            limit: 1,
            sortDescriptors: [sort]
        ) { _, samples, _ in
            guard let sample = samples?.first as? HKQuantitySample else { return }
            let bpm = sample.quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute()))
            DispatchQueue.main.async { completion(bpm) }
        }
        store.execute(query)
    }
}
