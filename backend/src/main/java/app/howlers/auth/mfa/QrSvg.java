package app.howlers.auth.mfa;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/**
 * Renders a QR code as a self-contained SVG on the server (D-042), so the TOTP secret never goes to a third-party QR
 * service. The SVG has only a background rect and one path: dark modules on white with a 4-module quiet zone, which
 * scanners need, whatever the page theme.
 */
public final class QrSvg {
    private QrSvg() {}

    public static String render(String text) {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed", e);
        }
        int quiet = 4;
        int size = m.getWidth() + quiet * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) path.append('M').append(x + quiet).append(' ').append(y + quiet).append("h1v1h-1z");
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code\">"
                + "<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#ffffff\"/>"
                + "<path fill=\"#000000\" d=\"" + path + "\"/></svg>";
    }
}
