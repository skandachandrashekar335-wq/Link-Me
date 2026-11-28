import AppKit
@preconcurrency import LocalAuthentication
import MacBridgeCore
import SwiftUI

/// Borderless, resizable floating NSWindow subclass capable of becoming key/main for full
/// keyboard, cursor, and trackpad input while sitting directly on the macOS wallpaper.
@MainActor
final class PhoneMirrorWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

/// Manages the standalone floating borderless Phone Mirror chassis, auto-hiding hover bar,
/// dynamic aspect ratio locking, and Touch ID / Password (LAContext) lock state.
@MainActor
final class PhoneMirrorWindowController: NSWindowController, NSWindowDelegate {
    private let viewModel: BridgeDashboardViewModel
    private let chassisView: PhoneChassisRootView
    private var lastLockedAspectRatio: CGSize = .zero
    private var isAlwaysOnTop: Bool = false

    init(viewModel: BridgeDashboardViewModel) {
        self.viewModel = viewModel
        let bezelPadding = PhoneChassisRootView.bezelInsets
        let defaultInnerSize = Self.computeInnerSize(
            width: viewModel.remoteScreenWidth,
            height: viewModel.remoteScreenHeight,
            targetWidth: 360
        )
        let totalSize = NSSize(
            width: defaultInnerSize.width + bezelPadding.left + bezelPadding.right,
            height: defaultInnerSize.height + bezelPadding.top + bezelPadding.bottom
        )
        let window = PhoneMirrorWindow(
            contentRect: NSRect(origin: .zero, size: totalSize),
            styleMask: [.borderless, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        window.isMovableByWindowBackground = true
        window.acceptsMouseMovedEvents = true
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.minSize = NSSize(width: 260, height: 520)

        self.chassisView = PhoneChassisRootView(frame: NSRect(origin: .zero, size: totalSize), viewModel: viewModel)
        super.init(window: window)

        window.delegate = self
        window.contentView = chassisView
        window.center()
        updateAspectRatio(
            screenWidth: viewModel.remoteScreenWidth,
            screenHeight: viewModel.remoteScreenHeight,
            resizeProportionally: false
        )

        chassisView.onCloseRequested = { [weak self] in
            self?.closeMirrorWindow()
        }
        chassisView.onMinimizeRequested = { [weak window] in
            window?.miniaturize(nil)
        }
        chassisView.onTogglePinRequested = { [weak self] in
            self?.toggleAlwaysOnTop()
        }
        chassisView.onLockRequested = { [weak self] in
            self?.lockMirrorAndAuthenticate(userInitiated: false)
        }
        chassisView.onUnlockRequested = { [weak self] in
            self?.authenticateAndStartIfNeeded()
        }
        syncFromViewModel()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func showAndAuthenticateIfNeeded() {
        guard let window else { return }
        showWindow(nil)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(chassisView.inputView)
        NSApp.activate(ignoringOtherApps: true)
        syncFromViewModel()

        if viewModel.isMirrorAuthenticated {
            viewModel.startPhoneMirrorStream()
            return
        }
        authenticateAndStartIfNeeded()
    }

    func authenticateAndStartIfNeeded() {
        if viewModel.preferences.mirrorAuthPolicy == .automaticallyAuthenticate {
            viewModel.isMirrorAuthenticated = true
            chassisView.setLocked(false)
            viewModel.startPhoneMirrorStream()
            return
        }

        chassisView.setLocked(true, message: "Authenticate to unlock \(viewModel.peerName)")
        let context = LAContext()
        context.localizedFallbackTitle = "Use Mac Password"
        let reason = "Unlock Link-Me Phone Mirroring for \(viewModel.peerName)"
        var authError: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &authError) {
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { [weak self] success, error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if success {
                        self.viewModel.isMirrorAuthenticated = true
                        self.chassisView.setLocked(false)
                        self.viewModel.startPhoneMirrorStream()
                        self.window?.makeFirstResponder(self.chassisView.inputView)
                    } else {
                        self.viewModel.isMirrorAuthenticated = false
                        self.chassisView.setLocked(
                            true,
                            message: error?.localizedDescription ?? "Authentication canceled"
                        )
                    }
                }
            }
        } else {
            viewModel.isMirrorAuthenticated = false
            chassisView.setLocked(
                true,
                message: authError?.localizedDescription ?? "Touch ID / Password authentication unavailable"
            )
        }
    }

    func lockMirrorAndAuthenticate(userInitiated: Bool) {
        viewModel.isMirrorAuthenticated = false
        viewModel.stopPhoneMirrorStream()
        chassisView.setLocked(true, message: "Phone Mirroring locked")
        if userInitiated {
            authenticateAndStartIfNeeded()
        }
    }

    func syncFromViewModel() {
        chassisView.updateDisplayState()
        updateAspectRatio(
            screenWidth: viewModel.remoteScreenWidth,
            screenHeight: viewModel.remoteScreenHeight,
            resizeProportionally: true
        )
    }

    func toggleAlwaysOnTop() {
        isAlwaysOnTop.toggle()
        window?.level = isAlwaysOnTop ? .floating : .normal
        chassisView.setAlwaysOnTop(isAlwaysOnTop)
    }

    func closeMirrorWindow() {
        chassisView.inputView.flushBufferedText()
        viewModel.stopPhoneMirrorStream()
        window?.orderOut(nil)
    }

    func windowDidBecomeKey(_ notification: Notification) {
        if !viewModel.isMirrorAuthenticated { return }
        window?.makeFirstResponder(chassisView.inputView)
    }

    private func updateAspectRatio(screenWidth: Int, screenHeight: Int, resizeProportionally: Bool) {
        guard let window else { return }
        let sw = CGFloat(max(320, screenWidth))
        let sh = CGFloat(max(320, screenHeight))
        let targetAspect = CGSize(width: sw, height: sh)
        guard abs(lastLockedAspectRatio.width - targetAspect.width) > 1 ||
              abs(lastLockedAspectRatio.height - targetAspect.height) > 1 else {
            return
        }
        lastLockedAspectRatio = targetAspect

        let pad = PhoneChassisRootView.bezelInsets
        let currentWidth = max(280, window.frame.width - pad.left - pad.right)
        let innerSize = Self.computeInnerSize(width: Int(sw), height: Int(sh), targetWidth: currentWidth)
        let totalW = innerSize.width + pad.left + pad.right
        let totalH = innerSize.height + pad.top + pad.bottom
        window.contentAspectRatio = NSSize(width: totalW, height: totalH)

        if resizeProportionally {
            var frame = window.frame
            let oldCenter = NSPoint(x: frame.midX, y: frame.midY)
            frame.size = NSSize(width: totalW, height: totalH)
            frame.origin = NSPoint(x: oldCenter.x - totalW / 2, y: oldCenter.y - totalH / 2)
            window.setFrame(frame, display: true, animate: true)
        }
    }

    private static func computeInnerSize(width: Int, height: Int, targetWidth: CGFloat) -> NSSize {
        let ratio = CGFloat(max(320, height)) / CGFloat(max(320, width))
        return NSSize(width: targetWidth, height: (targetWidth * ratio).rounded())
    }
}

// MARK: - Borderless Titanium Chassis Container + Hover Capsule Bar

@MainActor
private final class PhoneChassisRootView: NSView {
    static let bezelInsets = NSEdgeInsets(top: 26, left: 10, bottom: 12, right: 10)

    private let viewModel: BridgeDashboardViewModel
    let inputView: MirrorInputView

    private let titaniumLayer = CAShapeLayer()
    private let rimHighlightLayer = CAShapeLayer()
    private let speakerPillLayer = CAShapeLayer()
    private let lockBackdrop = NSVisualEffectView()
    private let lockIconView = NSImageView()
    private let lockTitleLabel = NSTextField(labelWithString: "Phone Mirroring Locked")
    private let lockButton = NSButton(title: "Unlock with Touch ID / Password", target: nil, action: nil)

    private let hoverBar = NSVisualEffectView()
    private let closeButton = HoverCircleButton(symbolName: "xmark", tint: NSColor.systemRed)
    private let minimizeButton = HoverCircleButton(symbolName: "minus", tint: NSColor.systemOrange)
    private let titleBadgeLabel = NSTextField(labelWithString: "Android • Standby")
    private let homeButton = HoverIconButton(symbolName: "house", tooltip: "Home (⌘1)")
    private let recentsButton = HoverIconButton(symbolName: "square.stack", tooltip: "App Switcher (⌘2)")
    private let backButton = HoverIconButton(symbolName: "chevron.backward", tooltip: "Back (Esc)")
    private let pinButton = HoverIconButton(symbolName: "pin", tooltip: "Always on Top")
    private let lockBarButton = HoverIconButton(symbolName: "lock.fill", tooltip: "Lock Screen Mirroring")

    private var hoverTrackingArea: NSTrackingArea?
    private var isMouseInTopZone = false

    var onCloseRequested: (() -> Void)?
    var onMinimizeRequested: (() -> Void)?
    var onTogglePinRequested: (() -> Void)?
    var onLockRequested: (() -> Void)?
    var onUnlockRequested: (() -> Void)?

    override var isFlipped: Bool { true }

    init(frame frameRect: NSRect, viewModel: BridgeDashboardViewModel) {
        self.viewModel = viewModel
        self.inputView = MirrorInputView(frame: .zero, viewModel: viewModel)
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor

        setupTitaniumChassis()
        setupScreenInputArea()
        setupLockOverlay()
        setupHoverTitlebar()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func setupTitaniumChassis() {
        titaniumLayer.fillColor = NSColor(calibratedRed: 0.08, green: 0.09, blue: 0.11, alpha: 0.98).cgColor
        layer?.addSublayer(titaniumLayer)

        rimHighlightLayer.fillColor = NSColor.clear.cgColor
        rimHighlightLayer.strokeColor = NSColor.white.withAlphaComponent(0.22).cgColor
        rimHighlightLayer.lineWidth = 1.4
        layer?.addSublayer(rimHighlightLayer)

        speakerPillLayer.fillColor = NSColor.white.withAlphaComponent(0.16).cgColor
        layer?.addSublayer(speakerPillLayer)
    }

    private func setupScreenInputArea() {
        inputView.wantsLayer = true
        inputView.layer?.cornerRadius = 30
        inputView.layer?.masksToBounds = true
        addSubview(inputView)
    }

    private func setupLockOverlay() {
        lockBackdrop.material = .hudWindow
        lockBackdrop.blendingMode = .withinWindow
        lockBackdrop.state = .active
        lockBackdrop.wantsLayer = true
        lockBackdrop.layer?.cornerRadius = 30
        lockBackdrop.layer?.masksToBounds = true
        lockBackdrop.isHidden = true

        lockIconView.image = NSImage(systemSymbolName: "touchid", accessibilityDescription: "Unlock")
        lockIconView.contentTintColor = .systemPink
        lockIconView.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 34, weight: .light)

        lockTitleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        lockTitleLabel.textColor = .labelColor
        lockTitleLabel.alignment = .center
        lockTitleLabel.maximumNumberOfLines = 2

        lockButton.bezelStyle = .rounded
        lockButton.target = self
        lockButton.action = #selector(handleUnlockPressed)

        lockBackdrop.addSubview(lockIconView)
        lockBackdrop.addSubview(lockTitleLabel)
        lockBackdrop.addSubview(lockButton)
        addSubview(lockBackdrop)
    }

    private func setupHoverTitlebar() {
        hoverBar.material = .hudWindow
        hoverBar.blendingMode = .withinWindow
        hoverBar.state = .active
        hoverBar.wantsLayer = true
        hoverBar.layer?.cornerRadius = 15
        hoverBar.layer?.borderWidth = 1
        hoverBar.layer?.borderColor = NSColor.white.withAlphaComponent(0.18).cgColor
        hoverBar.alphaValue = 0

        titleBadgeLabel.font = .monospacedSystemFont(ofSize: 10, weight: .semibold)
        titleBadgeLabel.textColor = .white
        titleBadgeLabel.lineBreakMode = .byTruncatingMiddle

        closeButton.onClick = { [weak self] in self?.onCloseRequested?() }
        minimizeButton.onClick = { [weak self] in self?.onMinimizeRequested?() }
        homeButton.onClick = { [weak self] in self?.viewModel.sendRemoteInput(.key("home")) }
        recentsButton.onClick = { [weak self] in self?.viewModel.sendRemoteInput(.key("recents")) }
        backButton.onClick = { [weak self] in self?.viewModel.sendRemoteInput(.key("back")) }
        pinButton.onClick = { [weak self] in self?.onTogglePinRequested?() }
        lockBarButton.onClick = { [weak self] in self?.onLockRequested?() }

        for btn in [closeButton, minimizeButton, titleBadgeLabel, homeButton, recentsButton, backButton, pinButton, lockBarButton] as [NSView] {
            hoverBar.addSubview(btn)
        }
        addSubview(hoverBar)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = hoverTrackingArea {
            removeTrackingArea(existing)
        }
        let topZone = NSRect(x: 0, y: 0, width: bounds.width, height: 76)
        let area = NSTrackingArea(
            rect: topZone,
            options: [.mouseEnteredAndExited, .mouseMoved, .activeAlways],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        hoverTrackingArea = area
    }

    override func mouseEntered(with event: NSEvent) {
        super.mouseEntered(with: event)
        setHoverBarVisible(true)
    }

    override func mouseExited(with event: NSEvent) {
        super.mouseExited(with: event)
        setHoverBarVisible(false)
    }

    private func setHoverBarVisible(_ visible: Bool) {
        guard visible != isMouseInTopZone else { return }
        isMouseInTopZone = visible
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.18
            hoverBar.animator().alphaValue = visible ? 1.0 : 0.0
        }
    }

    override func layout() {
        super.layout()
        let rect = bounds.insetBy(dx: 1, dy: 1)
        let radius: CGFloat = 42
        let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
        titaniumLayer.path = path
        rimHighlightLayer.path = path

        let pillW: CGFloat = 54
        let pillH: CGFloat = 5
        speakerPillLayer.path = CGPath(
            roundedRect: CGRect(x: (bounds.width - pillW) / 2, y: 11, width: pillW, height: pillH),
            cornerWidth: 2.5,
            cornerHeight: 2.5,
            transform: nil
        )

        let pad = Self.bezelInsets
        let screenFrame = NSRect(
            x: pad.left,
            y: pad.top,
            width: max(60, bounds.width - pad.left - pad.right),
            height: max(120, bounds.height - pad.top - pad.bottom)
        )
        inputView.frame = screenFrame
        lockBackdrop.frame = screenFrame

        let lockCenterY = screenFrame.height / 2
        lockIconView.frame = NSRect(x: (screenFrame.width - 42) / 2, y: lockCenterY - 62, width: 42, height: 42)
        lockTitleLabel.frame = NSRect(x: 20, y: lockCenterY - 10, width: screenFrame.width - 40, height: 38)
        lockButton.frame = NSRect(x: (screenFrame.width - 210) / 2, y: lockCenterY + 34, width: 210, height: 30)

        let barW = max(240, bounds.width - 28)
        hoverBar.frame = NSRect(x: 14, y: 12, width: barW, height: 30)
        closeButton.frame = NSRect(x: 8, y: 8, width: 14, height: 14)
        minimizeButton.frame = NSRect(x: 27, y: 8, width: 14, height: 14)

        let rightButtons: [NSView] = [lockBarButton, pinButton, backButton, recentsButton, homeButton]
        var rx = barW - 26
        for btn in rightButtons {
            btn.frame = NSRect(x: rx, y: 5, width: 20, height: 20)
            rx -= 22
        }
        let titleStart: CGFloat = 48
        titleBadgeLabel.frame = NSRect(x: titleStart, y: 8, width: max(40, rx - titleStart + 14), height: 14)
    }

    func updateDisplayState() {
        let transportTag: String
        if viewModel.bridgeState == .activeWifiStream {
            let fps = max(viewModel.remoteMirrorFps, 24)
            transportTag = "Wi-Fi \(fps)fps"
        } else {
            transportTag = "BLE"
        }
        titleBadgeLabel.stringValue = "\(viewModel.peerName) • \(transportTag)"
        inputView.updateRemoteImage(viewModel.remoteScreenImage, statusText: viewModel.mirrorStatusText)
    }

    func setAlwaysOnTop(_ pinned: Bool) {
        pinButton.updateSymbol(pinned ? "pin.fill" : "pin")
    }

    func setLocked(_ locked: Bool, message: String? = nil) {
        lockBackdrop.isHidden = !locked
        if let message {
            lockTitleLabel.stringValue = message
        }
    }

    @objc private func handleUnlockPressed() {
        onUnlockRequested?()
    }
}

// MARK: - AppKit Native Pointer, Scroll, and Keyboard Surface (0x0E REMOTE_INPUT)

@MainActor
final class MirrorInputView: NSView {
    private let viewModel: BridgeDashboardViewModel
    private let imageLayer = CALayer()
    private let standbyLabel = NSTextField(labelWithString: "Waiting for Android display stream...")
    private let precisionCursor: NSCursor

    private lazy var textBatcher = MirrorTextBatcher(intervalMs: 35) { [weak self] batched in
        self?.viewModel.sendRemoteInput(.text(batched))
    }

    private var pointerDownPoint: CGPoint?
    private var pointerDownTime: Date?
    private var holdWorkItem: DispatchWorkItem?
    private var didTriggerLongPress = false

    private var scrollAccumX: CGFloat = 0
    private var scrollAccumY: CGFloat = 0
    private var scrollFlushWorkItem: DispatchWorkItem?
    private var lastScrollAnchor = NormalizedMirrorPoint(x: 0.5, y: 0.5)

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override var mouseDownCanMoveWindow: Bool { false }

    init(frame frameRect: NSRect, viewModel: BridgeDashboardViewModel) {
        self.viewModel = viewModel
        self.precisionCursor = Self.makePrecisionCursor()
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor

        imageLayer.contentsGravity = .resizeAspect
        layer?.addSublayer(imageLayer)

        standbyLabel.font = .systemFont(ofSize: 12, weight: .medium)
        standbyLabel.textColor = NSColor.white.withAlphaComponent(0.65)
        standbyLabel.alignment = .center
        standbyLabel.maximumNumberOfLines = 3
        addSubview(standbyLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layout() {
        super.layout()
        imageLayer.frame = bounds
        standbyLabel.frame = NSRect(x: 18, y: (bounds.height - 48) / 2, width: max(40, bounds.width - 36), height: 48)
    }

    override func resetCursorRects() {
        super.resetCursorRects()
        addCursorRect(bounds, cursor: precisionCursor)
    }

    func updateRemoteImage(_ image: NSImage?, statusText: String) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if let image, let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) {
            imageLayer.contents = cgImage
            standbyLabel.isHidden = true
        } else {
            imageLayer.contents = nil
            standbyLabel.isHidden = false
            standbyLabel.stringValue = statusText
        }
        CATransaction.commit()
    }

    func flushBufferedText() {
        textBatcher.flushNow()
    }

    // MARK: Mouse Down / Drag / Long Press / Up

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let localPt = convert(event.locationInWindow, from: nil)
        pointerDownPoint = localPt
        pointerDownTime = Date()
        didTriggerLongPress = false

        holdWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self,
                  let startPt = self.pointerDownPoint,
                  let norm = MirrorInputTranslator.normalizePoint(
                    point: startPt,
                    contentRect: self.renderedImageRect(),
                    isFlipped: true
                  ) else {
                return
            }
            self.didTriggerLongPress = true
            self.spawnRipple(at: startPt, isLongOrDrag: true)
            self.viewModel.sendRemoteInput(.longPress(x: norm.x, y: norm.y, durationMs: 550))
        }
        holdWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.42, execute: work)
    }

    override func mouseDragged(with event: NSEvent) {
        guard let startPt = pointerDownPoint else { return }
        let currentPt = convert(event.locationInWindow, from: nil)
        if hypot(currentPt.x - startPt.x, currentPt.y - startPt.y) >= 6.0 {
            holdWorkItem?.cancel()
            holdWorkItem = nil
        }
    }

    override func mouseUp(with event: NSEvent) {
        holdWorkItem?.cancel()
        holdWorkItem = nil
        defer {
            pointerDownPoint = nil
            pointerDownTime = nil
            didTriggerLongPress = false
        }
        guard !didTriggerLongPress,
              let startPt = pointerDownPoint,
              let startedAt = pointerDownTime else {
            return
        }
        let endPt = convert(event.locationInWindow, from: nil)
        let contentRect = renderedImageRect()
        guard let startNorm = MirrorInputTranslator.normalizePoint(point: startPt, contentRect: contentRect, isFlipped: true),
              let endNorm = MirrorInputTranslator.normalizePoint(point: endPt, contentRect: contentRect, isFlipped: true) else {
            return
        }

        let distance = hypot(endPt.x - startPt.x, endPt.y - startPt.y)
        let elapsed = Date().timeIntervalSince(startedAt)
        let payload = MirrorInputTranslator.classifyPointerRelease(
            startPoint: startNorm,
            endPoint: endNorm,
            pixelDistance: distance,
            elapsedSeconds: elapsed
        )
        spawnRipple(at: endPt, isLongOrDrag: distance >= 6.0)
        viewModel.sendRemoteInput(payload)
    }

    // MARK: Two-Finger Trackpad Scroll

    override func scrollWheel(with event: NSEvent) {
        let localPt = convert(event.locationInWindow, from: nil)
        if let anchor = MirrorInputTranslator.normalizePoint(
            point: localPt,
            contentRect: renderedImageRect(),
            isFlipped: true
        ) {
            lastScrollAnchor = anchor
        }

        let multiplier: CGFloat = event.hasPreciseScrollingDeltas ? 1.0 : 8.0
        scrollAccumX += event.scrollingDeltaX * multiplier
        scrollAccumY += event.scrollingDeltaY * multiplier

        if scrollFlushWorkItem == nil {
            let flushWork = DispatchWorkItem { [weak self] in
                self?.flushAccumulatedScroll()
            }
            scrollFlushWorkItem = flushWork
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.045, execute: flushWork)
        }

        if event.phase == .ended || event.momentumPhase == .ended {
            flushAccumulatedScroll()
        }
    }

    private func flushAccumulatedScroll() {
        scrollFlushWorkItem?.cancel()
        scrollFlushWorkItem = nil
        let dx = scrollAccumX
        let dy = scrollAccumY
        scrollAccumX = 0
        scrollAccumY = 0

        if let swipe = MirrorInputTranslator.translateScrollToSwipe(
            deltaX: dx,
            deltaY: dy,
            anchor: lastScrollAnchor
        ) {
            viewModel.sendRemoteInput(swipe)
        }
    }

    // MARK: Keyboard & Shortcut Passthrough

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard window?.firstResponder === self else {
            return super.performKeyEquivalent(with: event)
        }
        if event.modifierFlags.contains(.command),
           let outcome = MirrorInputTranslator.translateKeyDown(
            keyCode: event.keyCode,
            characters: event.characters,
            charactersIgnoringModifiers: event.charactersIgnoringModifiers,
            modifierFlags: event.modifierFlags
           ) {
            handleKeyOutcome(outcome)
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    override func keyDown(with event: NSEvent) {
        guard let outcome = MirrorInputTranslator.translateKeyDown(
            keyCode: event.keyCode,
            characters: event.characters,
            charactersIgnoringModifiers: event.charactersIgnoringModifiers,
            modifierFlags: event.modifierFlags
        ) else {
            super.keyDown(with: event)
            return
        }
        handleKeyOutcome(outcome)
    }

    private func handleKeyOutcome(_ outcome: MirrorKeyEventOutcome) {
        switch outcome {
        case .remoteInput(let payload):
            textBatcher.flushNow()
            viewModel.sendRemoteInput(payload)
        case .bufferedText(let chars):
            textBatcher.enqueue(chars)
        case .copyClipboardThenKey:
            textBatcher.flushNow()
            viewModel.sendRemoteInput(.key("copy"))
        case .syncClipboardThenPaste:
            textBatcher.flushNow()
            viewModel.syncClipboardNow()
            viewModel.sendRemoteInput(.key("paste"))
        }
    }

    private func renderedImageRect() -> CGRect {
        let w = CGFloat(max(1, viewModel.remoteScreenWidth))
        let h = CGFloat(max(1, viewModel.remoteScreenHeight))
        guard bounds.width > 1, bounds.height > 1 else { return bounds }
        let scale = min(bounds.width / w, bounds.height / h)
        let drawW = w * scale
        let drawH = h * scale
        return CGRect(
            x: (bounds.width - drawW) / 2,
            y: (bounds.height - drawH) / 2,
            width: drawW,
            height: drawH
        )
    }

    private func spawnRipple(at point: CGPoint, isLongOrDrag: Bool) {
        let size: CGFloat = isLongOrDrag ? 26 : 18
        let ripple = CAShapeLayer()
        ripple.path = CGPath(
            ellipseIn: CGRect(x: -size / 2, y: -size / 2, width: size, height: size),
            transform: nil
        )
        ripple.position = point
        ripple.fillColor = NSColor.white.withAlphaComponent(0.16).cgColor
        ripple.strokeColor = NSColor.white.withAlphaComponent(0.75).cgColor
        ripple.lineWidth = 1.6
        layer?.addSublayer(ripple)

        let scaleAnim = CABasicAnimation(keyPath: "transform.scale")
        scaleAnim.fromValue = 0.7
        scaleAnim.toValue = 1.65

        let fadeAnim = CABasicAnimation(keyPath: "opacity")
        fadeAnim.fromValue = 1.0
        fadeAnim.toValue = 0.0

        let group = CAAnimationGroup()
        group.animations = [scaleAnim, fadeAnim]
        group.duration = 0.28
        group.isRemovedOnCompletion = true
        CATransaction.begin()
        CATransaction.setCompletionBlock { ripple.removeFromSuperlayer() }
        ripple.add(group, forKey: "mirrorRipple")
        CATransaction.commit()
    }

    private static func makePrecisionCursor() -> NSCursor {
        let size = NSSize(width: 16, height: 16)
        let image = NSImage(size: size, flipped: false) { rect in
            NSColor.black.withAlphaComponent(0.42).setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 1.5, dy: 1.5)).fill()

            NSColor.white.withAlphaComponent(0.92).setStroke()
            let ring = NSBezierPath(ovalIn: rect.insetBy(dx: 2.0, dy: 2.0))
            ring.lineWidth = 1.4
            ring.stroke()

            NSColor.white.setFill()
            NSBezierPath(ovalIn: NSRect(x: 6.5, y: 6.5, width: 3, height: 3)).fill()
            return true
        }
        return NSCursor(image: image, hotSpot: NSPoint(x: 8, y: 8))
    }
}

// MARK: - Hover Titlebar Helpers

@MainActor
private final class HoverCircleButton: NSView {
    private let symbolName: String
    private let tintColor: NSColor
    private let iconView = NSImageView()
    var onClick: (() -> Void)?

    init(symbolName: String, tint: NSColor) {
        self.symbolName = symbolName
        self.tintColor = tint
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = tint.withAlphaComponent(0.9).cgColor
        layer?.cornerRadius = 7

        iconView.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: symbolName)
        iconView.contentTintColor = .black.withAlphaComponent(0.75)
        iconView.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 7, weight: .bold)
        addSubview(iconView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        iconView.frame = bounds.insetBy(dx: 2, dy: 2)
    }

    override func mouseDown(with event: NSEvent) {
        onClick?()
    }
}

@MainActor
private final class HoverIconButton: NSButton {
    var onClick: (() -> Void)?

    init(symbolName: String, tooltip: String) {
        super.init(frame: .zero)
        isBordered = false
        bezelStyle = .regularSquare
        image = NSImage(systemSymbolName: symbolName, accessibilityDescription: tooltip)
        contentTintColor = .white.withAlphaComponent(0.9)
        symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 10, weight: .semibold)
        toolTip = tooltip
        target = self
        action = #selector(pressed)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func updateSymbol(_ symbolName: String) {
        image = NSImage(systemSymbolName: symbolName, accessibilityDescription: toolTip)
    }

    @objc private func pressed() {
        onClick?()
    }
}
