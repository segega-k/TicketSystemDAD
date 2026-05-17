package uz.inha.tickets.tickets;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import uz.inha.tickets.domain.Booking;
import uz.inha.tickets.domain.BookingSeat;

@Service
public class TicketPdfService {

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    public byte[] render(Booking b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, Color.BLACK);
            Paragraph title = new Paragraph(safe(b.event.title), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);

            Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.DARK_GRAY);
            String venue = b.event.venueName != null ? b.event.venueName : "Venue TBD";
            String when = b.event.startsAt != null ? HUMAN.format(b.event.startsAt) : "Date TBD";
            Paragraph sub = new Paragraph(venue + "  -  " + when, subFont);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(20f);
            doc.add(sub);

            byte[] qrBytes = qr("urn:ticket:" + b.id, 220);
            com.lowagie.text.Image qrImage = com.lowagie.text.Image.getInstance(qrBytes);
            qrImage.setAlignment(Element.ALIGN_CENTER);
            qrImage.scaleAbsolute(220, 220);
            doc.add(qrImage);

            Font seatFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
            String seatText = b.seats.stream()
                .map(bs -> "Row " + bs.seat.rowLabel + ", Seat " + bs.seat.seatNumber)
                .collect(Collectors.joining(" | "));
            if (seatText.isEmpty()) seatText = "(no seats)";
            Paragraph seatP = new Paragraph(seatText, seatFont);
            seatP.setAlignment(Element.ALIGN_CENTER);
            seatP.setSpacingBefore(16f);
            doc.add(seatP);

            Font priceFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.GRAY);
            String section = b.seats.stream()
                .map(bs -> bs.seat.section)
                .findFirst()
                .orElse("");
            Paragraph price = new Paragraph(
                section + "  -  Total: $" + String.format("%.2f", b.totalCents / 100.0),
                priceFont
            );
            price.setAlignment(Element.ALIGN_CENTER);
            price.setSpacingAfter(20f);
            doc.add(price);

            Font idFont = FontFactory.getFont(FontFactory.COURIER, 9, Color.DARK_GRAY);
            Paragraph idP = new Paragraph("Booking ID: " + b.id, idFont);
            idP.setAlignment(Element.ALIGN_CENTER);
            doc.add(idP);

            Font holderFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY);
            String holder = b.user.displayName != null && !b.user.displayName.isBlank()
                ? b.user.displayName
                : b.user.email;
            Paragraph holderP = new Paragraph("Holder: " + holder, holderFont);
            holderP.setAlignment(Element.ALIGN_CENTER);
            doc.add(holderP);

            Font footFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.LIGHT_GRAY);
            Paragraph foot = new Paragraph(
                "Please present this ticket at the entrance. Status: " + b.status,
                footFont
            );
            foot.setAlignment(Element.ALIGN_CENTER);
            foot.setSpacingBefore(40f);
            doc.add(foot);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to render ticket pdf", ex);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    static byte[] qr(String payload, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static String safe(String s) {
        return s == null ? "" : s;
    }

    static String seatLine(BookingSeat bs) {
        return "Row " + bs.seat.rowLabel + " - Seat " + bs.seat.seatNumber;
    }
}
