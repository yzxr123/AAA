package org.adguardian.app.ocr;

/** Category gate shared by direct OCR target clicks and close-candidate fallback. */
public final class OcrActionPolicy {
    private OcrActionPolicy() { }

    public static boolean shouldClose(boolean adEvidence,boolean commercialEvidence,
                                      boolean jumpEvidence,boolean shakeEvidence,
                                      boolean ordinaryEnabled,boolean jumpEnabled,boolean shakeEnabled) {
        if(jumpEvidence || shakeEvidence) {
            return (jumpEvidence && jumpEnabled) || (shakeEvidence && shakeEnabled);
        }
        return (adEvidence || commercialEvidence) && ordinaryEnabled;
    }
}
