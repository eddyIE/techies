package vn.techies.ecommerce.identity.service;

import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

/**
 * Validates an uploaded profile image.
 *
 * <p>The declared content type and the filename are both supplied by the client and are
 * trivially forged, so neither is trusted on its own. The actual leading bytes decide what
 * the file really is, and the declared type must agree with them — otherwise someone could
 * upload anything at all labelled {@code image/png}, and it would later be served back to
 * other users with that content type.
 */
final class ImageValidator {

    static final int MAX_BYTES = 2 * 1024 * 1024;   // 2 MB
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";

    // PNG: 89 50 4E 47 0D 0A 1A 0A  ("\x89PNG\r\n\x1a\n")
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    // JPEG: FF D8 FF  (SOI marker; the fourth byte varies by JFIF/Exif variant)
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private ImageValidator() {
    }

    /**
     * @return the canonical content type to store, derived from the bytes rather than the
     *         client's claim.
     */
    static String validate(byte[] data, String declaredContentType, String filename) {
        if (data == null || data.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The uploaded file is empty");
        }
        if (data.length > MAX_BYTES) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "Image must be 2MB or smaller, received " + (data.length / 1024) + "KB");
        }

        String actual = detect(data);
        if (actual == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                    "Only PNG and JPEG images are accepted"
                            + (filename == null ? "" : " (received '" + filename + "')"));
        }

        // A mismatch means the client is either buggy or lying; either way, refuse it rather
        // than storing bytes under a content type they do not match.
        String declared = normalise(declaredContentType);
        if (declared != null && !declared.equals(actual)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                    "File content is " + actual + " but was uploaded as " + declared);
        }
        return actual;
    }

    /** Identifies the format from its leading bytes. Null when it is neither PNG nor JPEG. */
    private static String detect(byte[] data) {
        if (startsWith(data, PNG_MAGIC)) {
            return PNG;
        }
        if (startsWith(data, JPEG_MAGIC)) {
            return JPEG;
        }
        return null;
    }

    /** Accepts the common spellings a client might send; anything else is left to fail. */
    private static String normalise(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String value = contentType.split(";")[0].trim().toLowerCase();
        return switch (value) {
            case "image/png" -> PNG;
            case "image/jpeg", "image/jpg", "image/pjpeg" -> JPEG;
            default -> value;
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
