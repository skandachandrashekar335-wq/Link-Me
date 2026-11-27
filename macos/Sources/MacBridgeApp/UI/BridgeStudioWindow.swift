import SwiftUI

struct BridgeStudioWindow: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel

    var body: some View {
        BridgeStudioContentView(
            viewModel: viewModel,
            isStandaloneWindow: true,
            onExpandWindow: nil
        )
        .frame(minWidth: 640, minHeight: 520)
    }
}
