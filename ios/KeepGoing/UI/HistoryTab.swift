import SwiftUI
import UIKit

/// All saved workouts, newest first (spec ios-app, req 5).
struct HistoryTab: View {
    @EnvironmentObject private var workout: WorkoutTracker

    var body: some View {
        TabPage {
            HStack {
                Text(L("card_history")).font(.system(size: 20, weight: .semibold))
                Spacer()
                if !workout.history.isEmpty {
                    Button(L("action_clear_history")) { workout.clearHistory() }
                }
            }
            .frame(minHeight: 44)
            if workout.history.isEmpty {
                Text(L("history_empty")).foregroundColor(.secondary)
            }
            ForEach(Array(workout.history.enumerated()), id: \.offset) { _, record in
                HistoryRow(record: record)
            }
        }
    }
}

struct HistoryRow: View {
    let record: WorkoutRecord

    var body: some View {
        let date = Date(timeIntervalSince1970: Double(record.startedAt) / 1000)
        VStack(alignment: .leading, spacing: 2) {
            Text(L("history_line", Format.historyDate.string(from: date), Format.duration(record.activeSec),
                   Format.distance(record.distanceM), record.calories))
                .fontWeight(.medium)
            Text(details).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var details: String {
        var parts = [L("stat_avg_rpm", record.avgRpm), L("stat_avg_resistance", record.avgResistance)]
        if let hr = record.avgHeartRate { parts.append(L("stat_avg_hr", hr)) }
        if let hr = record.maxHeartRate { parts.append(L("stat_max_hr", hr)) }
        return parts.joined(separator: " · ")
    }
}
