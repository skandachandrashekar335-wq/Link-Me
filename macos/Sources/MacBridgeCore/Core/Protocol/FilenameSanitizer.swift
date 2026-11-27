import Foundation

public enum FilenameSanitizationError: Error, Equatable, Sendable {
    case empty
    case containsControlOrNulCharacter
    case containsPathSeparator
    case containsTraversalSegment
    case leadingDotForbidden
    case tooLong
    case escapesDestinationRoot
}

/// Sanitizes untrusted filenames by stripping traversal/separators/control characters/leading dots,
/// truncating safely to 200 UTF-8 bytes while preserving file extensions, and falling back to "unnamed_file".
public enum FilenameSanitizer {
    public static let maxUtf8Bytes = 200
    public static let fallbackFilename = "unnamed_file"

    public static func sanitize(_ rawFilename: String) -> String {
        // 1. Strip NUL bytes and ASCII control characters
        let cleanedScalars = rawFilename.unicodeScalars.filter { scalar in
            scalar.value >= 0x20 && scalar.value != 0x7F
        }
        var candidate = String(String.UnicodeScalarView(cleanedScalars))

        // 2. Strip Windows drive prefixes (e.g. "C:") and split on '/' or '\'
        if candidate.count >= 2 {
            let second = candidate[candidate.index(after: candidate.startIndex)]
            if second == ":" && candidate.first?.isLetter == true {
                candidate = String(candidate.dropFirst(2))
            }
        }
        let segments = candidate.split(whereSeparator: { $0 == "/" || $0 == "\\" })
        candidate = segments.last.map(String.init) ?? ""

        // 3. Remove traversal tokens and leading dots
        candidate = candidate.replacingOccurrences(of: "..", with: "")
        while candidate.hasPrefix(".") {
            candidate.removeFirst()
        }
        candidate = candidate.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !candidate.isEmpty else {
            return fallbackFilename
        }

        return truncatePreservingExtension(candidate, maxBytes: maxUtf8Bytes)
    }

    public static func validateStrict(_ rawFilename: String) throws -> String {
        guard !rawFilename.isEmpty else { throw FilenameSanitizationError.empty }
        for scalar in rawFilename.unicodeScalars {
            if scalar.value < 0x20 || scalar.value == 0x7F {
                throw FilenameSanitizationError.containsControlOrNulCharacter
            }
        }
        if rawFilename.contains("/") || rawFilename.contains("\\") || rawFilename.contains(":") {
            throw FilenameSanitizationError.containsPathSeparator
        }
        if rawFilename == "." || rawFilename == ".." || rawFilename.contains("..") {
            throw FilenameSanitizationError.containsTraversalSegment
        }
        if rawFilename.hasPrefix(".") {
            throw FilenameSanitizationError.leadingDotForbidden
        }
        guard rawFilename.utf8.count <= maxUtf8Bytes else {
            throw FilenameSanitizationError.tooLong
        }
        return rawFilename
    }

    public static func resolveSafeURL(filename rawFilename: String, inside rootDirectory: URL) throws -> URL {
        let safeName = sanitize(rawFilename)
        let canonicalRoot = rootDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let targetURL = canonicalRoot.appendingPathComponent(safeName, isDirectory: false)
            .standardizedFileURL.resolvingSymlinksInPath()

        let rootPath = canonicalRoot.path.hasSuffix("/") ? canonicalRoot.path : canonicalRoot.path + "/"
        guard targetURL.path.hasPrefix(rootPath) else {
            throw FilenameSanitizationError.escapesDestinationRoot
        }
        return targetURL
    }

    private static func truncatePreservingExtension(_ filename: String, maxBytes: Int) -> String {
        if filename.utf8.count <= maxBytes {
            return filename
        }

        let ns = filename as NSString
        let ext = ns.pathExtension
        let dotExt = ext.isEmpty ? "" : ".\(ext)"
        let dotExtBytes = dotExt.utf8.count

        if dotExtBytes >= maxBytes {
            return prefixWithinBytes(filename, limit: maxBytes)
        }

        let stemBudget = maxBytes - dotExtBytes
        let stem = ns.deletingPathExtension
        let truncatedStem = prefixWithinBytes(stem, limit: stemBudget)
        let combined = truncatedStem + dotExt
        return combined.isEmpty ? fallbackFilename : combined
    }

    private static func prefixWithinBytes(_ str: String, limit: Int) -> String {
        var out = ""
        var byteCount = 0
        for ch in str {
            let chBytes = String(ch).utf8.count
            if byteCount + chBytes > limit { break }
            out.append(ch)
            byteCount += chBytes
        }
        return out
    }
}
