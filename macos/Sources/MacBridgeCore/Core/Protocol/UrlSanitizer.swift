import Foundation

public enum UnsafeUrlError: Error, Equatable, Sendable {
    case emptyOrTooLong
    case controlCharactersOrWhitespace
    case invalidSyntax
    case disallowedScheme(String)
    case missingHost
}

/// Strict URL validator allowing ONLY public-safe http:// or https:// web URLs.
public enum UrlSanitizer {
    public static let maxLength = 2048

    public static func validateStrict(_ rawUrl: String) throws -> URL {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= maxLength else {
            throw UnsafeUrlError.emptyOrTooLong
        }

        for scalar in trimmed.unicodeScalars {
            if scalar.value <= 0x20 || scalar.value == 0x7F {
                throw UnsafeUrlError.controlCharactersOrWhitespace
            }
        }

        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased() else {
            throw UnsafeUrlError.invalidSyntax
        }

        guard scheme == "http" || scheme == "https" else {
            throw UnsafeUrlError.disallowedScheme(scheme)
        }

        guard let host = components.host, !host.isEmpty,
              let url = components.url else {
            throw UnsafeUrlError.missingHost
        }

        return url
    }
}
